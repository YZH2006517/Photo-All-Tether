// Desktop check of the camera raw path: decodes a NEF (or any Bayer raw LibRaw reads)
// into the film engine's RawImage and develops it with the same engine the app runs.
//
//   nef_develop --assets <phone app assets> --in DSC_0001.NEF --out out.ppm
//               [--long-edge 2048] [--ev 0] [--kelvin 5500] [--tint 0] [--denoise 0.3]
//               [--set film=4] [--set paper=3] [--preview preview.jpg]
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#include "camera_raw.h"
#include "film_engine.h"

namespace {

bool read_file(const std::string &path, std::vector<uint8_t> &bytes)
{
  std::ifstream in(path, std::ios::binary);
  if(!in) return false;
  bytes.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
  return true;
}

bool load_lut(const std::string &path, film::Lut &lut, std::string &error)
{
  std::vector<uint8_t> bytes;
  if(!read_file(path, bytes))
  {
    error = "cannot read " + path;
    return false;
  }
  return film::parse_lut(bytes.data(), bytes.size(), lut, error);
}

bool write_ppm(const std::string &path, const film::DevelopResult &result)
{
  std::ofstream out(path, std::ios::binary);
  out << "P6\n" << result.width << " " << result.height << "\n255\n";
  for(size_t i = 0; i < result.rgba8.size(); i += 4) out.write(reinterpret_cast<const char *>(&result.rgba8[i]), 3);
  return static_cast<bool>(out);
}

} // namespace

int main(int argc, char **argv)
{
  std::string assets, in_path, out_path, preview_path, error;
  film::FilmParams params;
  film::DevelopOptions options;
  options.max_long_edge = 2048;
  float kelvin = 0, tint = 0;
  camraw::DecodeOptions decode_opts;
  for(int i = 1; i < argc; i++)
  {
    const std::string arg = argv[i];
    auto next = [&]() -> std::string {
      if(i + 1 >= argc)
      {
        std::cerr << arg << " needs a value\n";
        std::exit(2);
      }
      return argv[++i];
    };
    if(arg == "--assets") assets = next();
    else if(arg == "--in") in_path = next();
    else if(arg == "--out") out_path = next();
    else if(arg == "--preview") preview_path = next();
    else if(arg == "--long-edge") options.max_long_edge = std::stoi(next());
    else if(arg == "--ev") options.exposure_ev = std::stof(next());
    else if(arg == "--match-key") options.match_key = std::stof(next()); // the camera JPEG's key
    else if(arg == "--kelvin") kelvin = std::stof(next());
    else if(arg == "--tint") tint = std::stof(next());
    else if(arg == "--generic") decode_opts.force_generic = true; // the non-Bayer path, for testing
    else if(arg == "--denoise") options.raw_denoise = std::stof(next());
    else if(arg == "--chroma") options.chroma_denoise = std::stof(next());
    else if(arg == "--set")
    {
      const std::string kv = next();
      const size_t eq = kv.find('=');
      if(eq == std::string::npos ||
         !(film::set_stage(options.stages, kv.substr(0, eq), std::stof(kv.substr(eq + 1))) ||
           film::set_param(params, kv.substr(0, eq), std::stof(kv.substr(eq + 1)))))
      {
        std::cerr << "unknown parameter: " << kv << "\n";
        return 2;
      }
    }
    else
    {
      std::cerr << "unknown option " << arg << "\n";
      return 2;
    }
  }
  if(in_path.empty() || (out_path.empty() && preview_path.empty()))
  {
    std::cerr << "usage: nef_develop --assets DIR --in RAW --out OUT.ppm [--preview P.jpg] [--set name=value]...\n";
    return 2;
  }

  auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
  if(!preview_path.empty())
  {
    const auto t = std::chrono::steady_clock::now();
    const std::vector<uint8_t> jpeg = camraw::embedded_preview(in_path, error);
    if(jpeg.empty()) std::cerr << "preview: " << error << "\n";
    else
    {
      std::ofstream(preview_path, std::ios::binary).write(reinterpret_cast<const char *>(jpeg.data()), jpeg.size());
      std::cout << "preview: " << jpeg.size() << " bytes, " << ms(t, std::chrono::steady_clock::now()) << " ms\n";
    }
    if(out_path.empty()) return 0;
  }

  const auto t0 = std::chrono::steady_clock::now();
  camraw::DecodedRaw decoded;
  if(!camraw::decode_raw_file(in_path, decoded, error, decode_opts))
  {
    std::cerr << "decode: " << error << "\n";
    return 1;
  }
  if(decoded.bayer && kelvin > 0 && !camraw::apply_white(decoded, camraw::White::Kelvin, kelvin, tint, error))
  {
    std::cerr << "white: " << error << "\n";
    return 1;
  }
  camraw::estimate_noise(decoded);
  const auto t1 = std::chrono::steady_clock::now();
  const film::RawImage &raw = decoded.raw;
  const camraw::RawMeta &m = decoded.meta;
  std::printf("raw: %s %s, lens %s, ISO %.0f, 1/%.0f s, f/%.1f, %.0f mm\n", m.make.c_str(), m.model.c_str(),
              m.lens.c_str(), m.iso, m.shutter > 0 ? 1 / m.shutter : 0, m.aperture, m.focal);
  std::printf("     %dx%d (sensor %dx%d, %d bit, %s), cfa %d%d%d%d, black %.0f/%.0f/%.0f/%.0f white %.0f\n", raw.width,
              raw.height, m.raw_width, m.raw_height, m.bits, m.decoder.c_str(), raw.cfa_colour[0], raw.cfa_colour[1],
              raw.cfa_colour[2], raw.cfa_colour[3], raw.black[0], raw.black[1], raw.black[2], raw.black[3], raw.white);
  std::printf("     wb %.3f %.3f %.3f (as shot neutral %.3f %.3f %.3f), orientation %d, decode %.0f ms\n", raw.wb[0],
              raw.wb[1], raw.wb[2], decoded.as_shot_neutral[0], decoded.as_shot_neutral[1], decoded.as_shot_neutral[2],
              raw.orientation, ms(t0, t1));
  if(decoded.bayer) std::printf("     key %.4f, noise %.3g %.3g\n", film::raw_key(raw), raw.noise_scale, raw.noise_offset);

  film::Lut filmsim, spectra;
  if(!load_lut(assets + "/filmsim/filmsim.lut", filmsim, error) ||
     !load_lut(assets + "/filmsim/spectra-em.lut", spectra, error))
  {
    std::cerr << error << "\n";
    return 1;
  }
  auto engine = film::FilmEngine::create(filmsim, spectra, error, false);
  if(!engine)
  {
    std::cerr << "engine: " << error << "\n";
    return 1;
  }
  const auto t2 = std::chrono::steady_clock::now();
  film::SourceImage source;
  std::vector<float> generic;
  if(decoded.bayer) source.raw = &raw;
  else
  {
    int gw = 0, gh = 0;
    if(!camraw::generic_pixels(decoded, options.max_long_edge, kelvin > 0 ? camraw::White::Kelvin : camraw::White::AsShot,
                              kelvin, tint, generic, gw, gh, error))
    {
      std::cerr << "generic: " << error << "\n";
      return 1;
    }
    source.rgba32f = generic.data();
    source.width = gw;
    source.height = gh;
    source.space = film::ColourSpace::LinearRec2020;
    const float key = camraw::rgba_key(generic, gw, gh);
    if(options.match_key > 0 && key > 0) options.exposure_ev += std::log2(options.match_key / key);
    std::printf("generic: %s, %dx%d -> %dx%d, key %.4f\n", decoded.demosaic.c_str(), decoded.rgb_width,
                decoded.rgb_height, gw, gh, key);
  }
  film::DevelopResult result;
  if(!engine->develop(source, params, options, result, error))
  {
    std::cerr << "develop: " << error << "\n";
    return 1;
  }
  const auto t3 = std::chrono::steady_clock::now();
  if(!write_ppm(out_path, result))
  {
    std::cerr << "cannot write " << out_path << "\n";
    return 1;
  }
  std::cout << "device: " << engine->device().name << "\n"
            << "film: " << film::kFilmStocks[params.film] << " / paper: " << film::kPapers[params.paper] << "\n"
            << "out: " << result.width << "x" << result.height << ", develop " << ms(t2, t3) << " ms (GPU "
            << result.gpu_ms << " ms)\n";
  return 0;
}
