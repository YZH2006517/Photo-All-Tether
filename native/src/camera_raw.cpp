#include "camera_raw.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <memory>

#include "libraw/libraw.h"

namespace camraw {
namespace {

using Mat3 = std::array<double, 9>;
using Vec3 = std::array<double, 3>;

Mat3 mul(const Mat3 &a, const Mat3 &b)
{
  Mat3 r{};
  for(int i = 0; i < 3; i++)
    for(int j = 0; j < 3; j++)
      for(int k = 0; k < 3; k++) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
  return r;
}

Vec3 mulv(const Mat3 &m, const Vec3 &v)
{
  return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
          m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}

bool invert(const Mat3 &m, Mat3 &out)
{
  const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                     m[2] * (m[3] * m[7] - m[4] * m[6]);
  if(std::fabs(det) < 1e-12) return false;
  const double d = 1.0 / det;
  out = {(m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
         (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
         (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d};
  return true;
}

constexpr Vec3 kD65 = {0.95047, 1.0, 1.08883};
constexpr Mat3 kXyzToRec2020 = {1.7166512, -0.3556708, -0.2533663, -0.6666844, 1.6164812,
                                0.0157685, 0.0176399,  -0.0427706, 0.9421031};
constexpr Mat3 kBradford = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};

// Chromatic adaptation from white [from] to white [to] (XYZ), Bradford cone space.
Mat3 bradford(const Vec3 &from, const Vec3 &to)
{
  const Vec3 a = mulv(kBradford, from), b = mulv(kBradford, to);
  const Mat3 scale = {b[0] / a[0], 0, 0, 0, b[1] / a[1], 0, 0, 0, b[2] / a[2]};
  Mat3 inverse;
  invert(kBradford, inverse);
  return mul(inverse, mul(scale, kBradford));
}

// Chromaticity of a light at [kelvin]: the CIE daylight locus from 4000 K, the Planckian
// locus below (Kim et al. cubic fit), as cameras' Kelvin settings follow.
void kelvin_xy(double t, double &x, double &y)
{
  t = std::clamp(t, 1700.0, 25000.0);
  if(t >= 4000.0)
  {
    x = t <= 7000.0 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                    : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
    y = -3.0 * x * x + 2.870 * x - 0.275;
  }
  else
  {
    x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
    y = t <= 2222.0 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                    : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
  }
}

// raw.wb and raw.camera_to_rec2020 from the camera RGB of a neutral, as dng.cpp does with
// a DNG's ColorMatrix and AsShotNeutral.
bool set_neutral(DecodedRaw &d, const double neutral_in[3], std::string &error)
{
  double neutral[3];
  for(int c = 0; c < 3; c++) neutral[c] = neutral_in[c] > 1e-6 ? neutral_in[c] : 1.0;
  const double inv_min = std::min({1 / neutral[0], 1 / neutral[1], 1 / neutral[2]});
  for(int c = 0; c < 3; c++) d.raw.wb[c] = static_cast<float>((1 / neutral[c]) / inv_min);

  Mat3 cm, xyz_from_camera;
  std::copy(std::begin(d.xyz_to_camera), std::end(d.xyz_to_camera), cm.begin());
  if(!invert(cm, xyz_from_camera))
  {
    error = "相机色彩矩阵不可逆";
    return false;
  }
  const Mat3 n = {neutral[0], 0, 0, 0, neutral[1], 0, 0, 0, neutral[2]};
  const Mat3 m = mul(xyz_from_camera, n);
  Vec3 scene_white = mulv(m, {1, 1, 1});
  const double y = scene_white[1];
  if(!(y > 0))
  {
    error = "白平衡无效";
    return false;
  }
  for(double &v : scene_white) v /= y;
  Mat3 camera_to_d65 = mul(bradford(scene_white, kD65), m);
  for(double &v : camera_to_d65) v /= y;
  const Mat3 to_rec2020 = mul(kXyzToRec2020, camera_to_d65);
  for(int i = 0; i < 9; i++) d.raw.camera_to_rec2020[i] = static_cast<float>(to_rec2020[i]);
  return true;
}

// Camera RGB of a neutral from white-balance multipliers (green = 1), or false.
bool neutral_from_multipliers(const float mul[4], double out[3])
{
  if(!(mul[0] > 0 && mul[1] > 0 && mul[2] > 0)) return false;
  for(int c = 0; c < 3; c++) out[c] = mul[1] / mul[c];
  return true;
}

// Metadata, colour matrix and as-shot white: the same for both paths.
void read_meta(LibRaw &lr, DecodedRaw &out)
{
  const libraw_colordata_t &C = lr.imgdata.color;
  const libraw_iparams_t &P = lr.imgdata.idata;
  const libraw_image_sizes_t &S = lr.imgdata.sizes;
  out.has_matrix = false;
  for(int c = 0; c < 3; c++)
    for(int j = 0; j < 3; j++)
    {
      out.xyz_to_camera[c * 3 + j] = C.cam_xyz[c][j];
      out.has_matrix = out.has_matrix || C.cam_xyz[c][j] != 0.0f;
    }
  if(!out.has_matrix && P.dng_version)
  { // A DNG (Leica, Pentax, Ricoh, Sigma fp, Hasselblad, DJI, phones): LibRaw keeps its
    // ColorMatrix apart. The one for D65 (illuminant 21), else the one not for tungsten (17);
    // its CameraCalibration applied (CC x CM, as the DNG spec's XYZ-to-camera).
    const libraw_dng_color_t *dc = C.dng_color;
    auto filled = [](const libraw_dng_color_t &d) {
      for(int c = 0; c < 3; c++)
        for(int j = 0; j < 3; j++)
          if(d.colormatrix[c][j] != 0.0f) return true;
      return false;
    };
    int pick = -1;
    for(int i = 0; i < 2; i++)
      if(filled(dc[i]) && dc[i].illuminant == 21) pick = i;
    for(int i = 0; i < 2 && pick < 0; i++)
      if(filled(dc[i]) && dc[i].illuminant != 17) pick = i;
    for(int i = 0; i < 2 && pick < 0; i++)
      if(filled(dc[i])) pick = i;
    if(pick >= 0)
    {
      const libraw_dng_color_t &d = dc[pick];
      bool calibrated = false;
      for(int c = 0; c < 3; c++)
        for(int j = 0; j < 3; j++) calibrated = calibrated || d.calibration[c][j] != 0.0f;
      for(int c = 0; c < 3; c++)
        for(int j = 0; j < 3; j++)
        {
          double v = 0;
          for(int k = 0; k < 3; k++)
            v += (calibrated ? d.calibration[c][k] : (c == k ? 1.0 : 0.0)) * d.colormatrix[k][j];
          out.xyz_to_camera[c * 3 + j] = v;
        }
      out.has_matrix = true;
    }
  }
  if(!neutral_from_multipliers(C.pre_mul, out.daylight_neutral))
    for(double &v : out.daylight_neutral) v = 1.0;
  if(!neutral_from_multipliers(C.cam_mul, out.as_shot_neutral))
    std::copy(std::begin(out.daylight_neutral), std::end(out.daylight_neutral), out.as_shot_neutral);
  const int bits = C.raw_bps > 0 ? C.raw_bps : 14;
  RawMeta &m = out.meta;
  m.make = P.normalized_make[0] ? P.normalized_make : P.make;
  m.model = P.normalized_model[0] ? P.normalized_model : P.model;
  m.lens = lr.imgdata.lens.Lens[0] ? lr.imgdata.lens.Lens : lr.imgdata.lens.makernotes.Lens;
  m.software = P.software;
  m.iso = lr.imgdata.other.iso_speed;
  m.shutter = lr.imgdata.other.shutter;
  m.aperture = lr.imgdata.other.aperture;
  m.focal = lr.imgdata.other.focal_len;
  m.timestamp = static_cast<int64_t>(lr.imgdata.other.timestamp);
  m.shot_order = lr.imgdata.other.shot_order;
  m.raw_width = S.raw_width;
  m.raw_height = S.raw_height;
  m.bits = bits;
  m.decoder = lr.unpack_function_name();
}

// Any sensor LibRaw reads: demosaiced by LibRaw to linear Rec.2020, 16 bit, upright.
bool generic_decode(LibRaw &lr, DecodedRaw &out, std::string &error, const DecodeOptions &opts)
{
  libraw_output_params_t &O = lr.imgdata.params;
  O.output_color = 8;       // Rec.2020
  O.gamm[0] = 1.0;          // linear
  O.gamm[1] = 1.0;
  O.no_auto_bright = 1;
  O.output_bps = 16;
  O.use_camera_wb = 1;
  O.use_auto_wb = 0;
  O.highlight = 0;          // clip: the film shoulder rolls the highlights off
  const libraw_image_sizes_t &S = lr.imgdata.sizes;
  const int long_edge = std::max<int>(S.width, S.height);
  O.half_size = opts.long_edge_hint > 0 && long_edge / 2 >= opts.long_edge_hint ? 1 : 0;
  const int processed = lr.dcraw_process();
  if(processed != LIBRAW_SUCCESS)
  {
    error = std::string("RAW 处理失败：") + libraw_strerror(processed);
    return false;
  }
  int code = 0;
  libraw_processed_image_t *img = lr.dcraw_make_mem_image(&code);
  if(!img || img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 16)
  {
    if(img) LibRaw::dcraw_clear_mem(img);
    error = std::string("RAW 输出失败：") + libraw_strerror(code);
    return false;
  }
  out.bayer = false;
  out.rgb_width = img->width;
  out.rgb_height = img->height;
  const size_t n = static_cast<size_t>(img->width) * img->height * 3;
  out.rgb.resize(n);
  std::memcpy(out.rgb.data(), img->data, n * sizeof(uint16_t));
  LibRaw::dcraw_clear_mem(img);

  // balanced camera RGB -> Rec.2020 as LibRaw converted it (rgb_cam is camera -> sRGB)
  static const Mat3 kSrgbToXyz = {0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522,
                                  0.0721750, 0.0193339, 0.1191920, 0.9503041};
  Mat3 rgb_cam;
  for(int i = 0; i < 3; i++)
    for(int j = 0; j < 3; j++) rgb_cam[i * 3 + j] = lr.imgdata.color.rgb_cam[i][j];
  const Mat3 m = mul(kXyzToRec2020, mul(kSrgbToXyz, rgb_cam));
  std::copy(m.begin(), m.end(), out.rec2020_from_camera);

  const libraw_iparams_t &P = lr.imgdata.idata;
  out.demosaic = P.filters == 9 ? "X-Trans（LibRaw 3-pass）" : P.filters == 0 ? "无马赛克（Foveon/线性）"
               : P.colors == 4 ? "四色传感器（LibRaw）" : "拜耳（LibRaw 通用路径）";
  if(O.half_size) out.demosaic += "，半尺寸";
  film::RawImage &r = out.raw;
  r = film::RawImage{};
  r.width = out.rgb_width;
  r.height = out.rgb_height;
  r.orientation = 1; // the mem image is already upright
  return true;
}

// Nikon's High Efficiency raws (NEF compression 13 = HE, 14 = HE*, the TicoRAW codec of the Z 8,
// Z 9, Z f, Z 6III ...): LibRaw decodes them from its 202611 snapshot on; should one still fail
// (a newer variant), say so in words the user can act on. Empty for every other file.
std::string unsupported_reason(LibRaw &lr)
{
  if(lr.imgdata.idata.maker_index != LIBRAW_CAMERAMAKER_Nikon) return {};
  const int c = lr.imgdata.makernotes.nikon.NEFCompression;
  if(c != 13 && c != 14) return {};
  return std::string("尼康高效率 RAW（") + (c == 14 ? "HE★" : "HE") +
         "）暂不支持：请在相机菜单「RAW 压缩」里改成「无损压缩」";
}

// A body newer than this LibRaw's colour tables opens and decodes (its maker's format is
// known) but has no colour matrix: LibRaw would render camera RGB as if it were sRGB (green,
// flat). A recent model of the same maker stands in; sensors of one maker and generation are
// close. The file's own black and white levels stay (the table would set the stand-in's).
// Returns the stand-in's name, or empty (a DNG, a known model, a maker without one here).
std::string borrow_matrix(LibRaw &lr, bool test = false)
{
  const libraw_colordata_t &C = lr.imgdata.color;
  if(lr.imgdata.idata.dng_version && !test) return {}; // a DNG carries its own matrix
  for(int c = 0; c < 3; c++)
    for(int j = 0; j < 3; j++)
      if(C.cam_xyz[c][j] != 0.0f) return {};
  if(lr.imgdata.idata.colors != 3) return {}; // four-colour and Foveon sensors: LibRaw's own
  struct Standin
  {
    unsigned maker, table_maker;
    const char *model;
  };
  static const Standin kStandins[] = {
      {LIBRAW_CAMERAMAKER_Nikon, LIBRAW_CAMERAMAKER_Nikon, "Z 8"},
      {LIBRAW_CAMERAMAKER_Canon, LIBRAW_CAMERAMAKER_Canon, "EOS R6 Mark II"},
      {LIBRAW_CAMERAMAKER_Sony, LIBRAW_CAMERAMAKER_Sony, "ILCE-7M4"},
      {LIBRAW_CAMERAMAKER_Fujifilm, LIBRAW_CAMERAMAKER_Fujifilm, "X-T5"},
      {LIBRAW_CAMERAMAKER_Panasonic, LIBRAW_CAMERAMAKER_Panasonic, "DC-S5M2"},
      {LIBRAW_CAMERAMAKER_Leica, LIBRAW_CAMERAMAKER_Panasonic, "DC-S5M2"}, // L-mount siblings
      {LIBRAW_CAMERAMAKER_Olympus, LIBRAW_CAMERAMAKER_Olympus, "OM-1"},
      {LIBRAW_CAMERAMAKER_OmDigital, LIBRAW_CAMERAMAKER_Olympus, "OM-1"},
      {LIBRAW_CAMERAMAKER_Pentax, LIBRAW_CAMERAMAKER_Pentax, "K-3 Mark III"},
      {LIBRAW_CAMERAMAKER_Ricoh, LIBRAW_CAMERAMAKER_Pentax, "K-3 Mark III"},
  };
  const unsigned maker = lr.imgdata.idata.maker_index;
  for(const Standin &s : kStandins)
  {
    if(s.maker != maker) continue;
    libraw_colordata_t &W = lr.imgdata.color;
    const unsigned black = W.black, maximum = W.maximum;
    unsigned cblack[LIBRAW_CBLACK_SIZE];
    std::memcpy(cblack, W.cblack, sizeof(cblack));
    const int found = lr.adobe_coeff(s.table_maker, s.model, 0); // also sets rgb_cam for LibRaw's path
    W.black = black;
    W.maximum = maximum;
    std::memcpy(W.cblack, cblack, sizeof(cblack));
    if(found) return s.model;
  }
  return {};
}

bool fill_from(LibRaw &lr, int opened, DecodedRaw &out, std::string &error, const DecodeOptions &opts)
{
  if(opened != LIBRAW_SUCCESS)
  {
    error = unsupported_reason(lr);
    if(error.empty()) error = std::string("无法打开 RAW：") + libraw_strerror(opened);
    return false;
  }
  const bool test = opts.test_unknown_maker >= 0;
  if(test)
  {
    lr.imgdata.idata.maker_index = static_cast<unsigned>(opts.test_unknown_maker);
    std::memset(lr.imgdata.color.cam_xyz, 0, sizeof(lr.imgdata.color.cam_xyz));
  }
  out.borrowed_matrix = borrow_matrix(lr, test);
  const int unpacked = lr.unpack();
  if(unpacked != LIBRAW_SUCCESS)
  {
    // a later LibRaw may decode HE: only explained when this one could not
    error = unsupported_reason(lr);
    if(error.empty()) error = std::string("RAW 解码失败：") + libraw_strerror(unpacked);
    return false;
  }
  const libraw_image_sizes_t &S = lr.imgdata.sizes;
  const libraw_colordata_t &C = lr.imgdata.color;
  const libraw_iparams_t &P = lr.imgdata.idata;
  const uint16_t *raw = lr.imgdata.rawdata.raw_image;
  read_meta(lr, out);
  // the engine demosaics 2x2 Bayer itself; everything else LibRaw does first
  if(opts.force_generic || !raw || P.filters < 1000 || P.colors != 3) return generic_decode(lr, out, error, opts);
  const int w = S.width & ~1, h = S.height & ~1;
  if(w < 64 || h < 64 || S.top_margin + h > S.raw_height || S.left_margin + w > S.raw_width)
  {
    error = "RAW 尺寸无效";
    return false;
  }
  film::RawImage &r = out.raw;
  r = film::RawImage{};
  r.width = w;
  r.height = h;
  r.cfa.resize(static_cast<size_t>(w) * h);
  const size_t pitch = S.raw_pitch / 2;
  for(int y = 0; y < h; y++)
    std::memcpy(&r.cfa[static_cast<size_t>(y) * w], raw + (y + S.top_margin) * pitch + S.left_margin,
                static_cast<size_t>(w) * sizeof(uint16_t));

  // 2x2 positions (0,0) (1,0) (0,1) (1,1) as (x,y); LibRaw's colour 3 is the second green.
  for(int i = 0; i < 4; i++)
  {
    const int x = i & 1, y = i >> 1;
    const int fc = lr.COLOR(y, x);
    r.cfa_colour[i] = fc == 3 ? 1 : std::clamp(fc, 0, 2);
    float black = static_cast<float>(C.black + C.cblack[std::clamp(fc, 0, 3)]);
    if(C.cblack[4] > 0 && C.cblack[5] > 0 && 6 + C.cblack[4] * C.cblack[5] <= LIBRAW_CBLACK_SIZE)
      black += static_cast<float>(C.cblack[6 + (y % C.cblack[4]) * C.cblack[5] + x % C.cblack[5]]);
    r.black[i] = black;
  }
  const int bits = C.raw_bps > 0 ? C.raw_bps : 14;
  r.white = C.maximum > 0 ? static_cast<float>(C.maximum) : static_cast<float>((1u << bits) - 1);
  switch(S.flip)
  {
  case 3: r.orientation = 3; break;
  case 5: r.orientation = 8; break;
  case 6: r.orientation = 6; break;
  default: r.orientation = 1; break;
  }

  if(!out.has_matrix)
  {
    // a Bayer camera newer than this LibRaw's colour tables: LibRaw's own path still renders it
    return generic_decode(lr, out, error, opts);
  }
  out.bayer = true;
  out.rgb.clear();
  return set_neutral(out, out.as_shot_neutral, error);
}

} // namespace

namespace {

// A DNG LibRaw cannot decode (JPEG XL tiles: LibRaw reads those only through Adobe's DNG SDK),
// through the film engine's own DNG reader (engine/src/dng.cpp, with libjxl); LibRaw still
// gives the metadata when it can open the file. False (error set) for anything else.
bool engine_dng(const uint8_t *data, size_t size, DecodedRaw &out, std::string &error, const DecodeOptions &opts)
{
  if(size < 8 || !((data[0] == 'I' && data[1] == 'I') || (data[0] == 'M' && data[1] == 'M'))) return false; // not TIFF
  film::RawImage r;
  if(!film::parse_dng(data, size, r, error, nullptr, opts.long_edge_hint)) return false;
  out = DecodedRaw{}; // nothing left from LibRaw's failed attempt
  auto lr = std::make_unique<LibRaw>();
  if(lr->open_buffer(data, size) == LIBRAW_SUCCESS) read_meta(*lr, out);
  out.raw = std::move(r);
  out.bayer = true; // the engine takes it as it is (a mosaic, or linear RGB)
  out.engine_dng = true;
  out.rgb.clear();
  out.meta.decoder = "Photo All DNG（LibRaw 不支持的压缩，如 JPEG XL）";
  return true;
}

bool read_file(const std::string &path, std::vector<uint8_t> &bytes)
{
  FILE *f = std::fopen(path.c_str(), "rb");
  if(!f) return false;
  std::fseek(f, 0, SEEK_END);
  const long n = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  bytes.resize(n > 0 ? static_cast<size_t>(n) : 0);
  const bool ok = n > 0 && std::fread(bytes.data(), 1, bytes.size(), f) == bytes.size();
  std::fclose(f);
  return ok;
}

} // namespace

// A .dng goes to the film engine's own DNG reader first (as the Photo All phone app reads
// them): it applies the lens-shading GainMap LibRaw leaves out (a phone's corners stay dark
// otherwise), reads linear DNGs at any size and their JPEG XL tiles, and the file's NoiseProfile.
// LibRaw takes what it does not read (lossy JPEG, floating point, linearisation tables ...), and
// every other format; a DNG LibRaw cannot read either goes back to the engine reader's error.
bool decode_raw_file(const std::string &path, DecodedRaw &out, std::string &error, const DecodeOptions &opts)
{
  std::string ext = path.size() > 4 ? path.substr(path.size() - 4) : "";
  for(char &c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  const bool dng = ext == ".dng";
  std::string dng_error;
  if(dng || opts.test_engine_dng)
  {
    std::vector<uint8_t> bytes;
    if(read_file(path, bytes) && engine_dng(bytes.data(), bytes.size(), out, dng_error, opts)) return true;
    if(opts.test_engine_dng)
    {
      error = dng_error.empty() ? "DNG 读取失败" : dng_error;
      return false;
    }
  }
  auto lr = std::make_unique<LibRaw>();
  if(fill_from(*lr, lr->open_file(path.c_str()), out, error, opts)) return true;
  if(!dng_error.empty()) error += "；DNG：" + dng_error;
  return false;
}

bool decode_raw_buffer(const uint8_t *data, size_t size, DecodedRaw &out, std::string &error, const DecodeOptions &opts)
{
  {
    auto lr = std::make_unique<LibRaw>();
    if(fill_from(*lr, lr->open_buffer(data, size), out, error, opts)) return true;
  }
  std::string dng_error;
  if(engine_dng(data, size, out, dng_error, opts)) return true;
  if(!dng_error.empty()) error += "；DNG：" + dng_error;
  return false;
}

bool generic_pixels(const DecodedRaw &d, int max_long_edge, White white, float kelvin, float tint,
                    std::vector<float> &rgba, int &width, int &height, std::string &error)
{
  if(d.bayer || d.rgb.empty())
  {
    error = "没有通用路径的图像";
    return false;
  }
  // A colour temperature: the camera balanced the picture to its as-shot neutral; re-balance
  // in camera space to the new one (balanced = raw / neutral), Rec.2020 on both sides.
  Mat3 correct = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  if(white == White::Kelvin && d.has_matrix)
  {
    double x, y;
    kelvin_xy(kelvin, x, y);
    Mat3 cm;
    std::copy(std::begin(d.xyz_to_camera), std::end(d.xyz_to_camera), cm.begin());
    const Vec3 cam = mulv(cm, {x / y, 1.0, (1.0 - x - y) / y});
    if(cam[0] > 0 && cam[1] > 0 && cam[2] > 0)
    {
      double target[3] = {cam[0] / cam[1], 1.0, cam[2] / cam[1]};
      target[1] *= std::exp(0.25 * std::clamp(tint, -1.0f, 1.0f));
      Mat3 to2020, from2020;
      std::copy(std::begin(d.rec2020_from_camera), std::end(d.rec2020_from_camera), to2020.begin());
      if(invert(to2020, from2020))
      {
        const Mat3 scale = {d.as_shot_neutral[0] / target[0], 0, 0, 0, d.as_shot_neutral[1] / target[1], 0, 0, 0,
                            d.as_shot_neutral[2] / target[2]};
        correct = mul(to2020, mul(scale, from2020));
      }
    }
  }
  const int sw = d.rgb_width, sh = d.rgb_height;
  const int f = std::max(1, (std::max(sw, sh) + std::max(16, max_long_edge) - 1) / std::max(16, max_long_edge));
  width = sw / f;
  height = sh / f;
  rgba.assign(static_cast<size_t>(width) * height * 4, 1.0f);
  const float norm = 1.0f / (65535.0f * f * f);
  for(int y = 0; y < height; y++)
    for(int x = 0; x < width; x++)
    {
      double sum[3] = {0, 0, 0};
      for(int dy = 0; dy < f; dy++)
      {
        const uint16_t *p = &d.rgb[(static_cast<size_t>(y * f + dy) * sw + x * f) * 3];
        for(int dx = 0; dx < f; dx++, p += 3)
        {
          sum[0] += p[0];
          sum[1] += p[1];
          sum[2] += p[2];
        }
      }
      const Vec3 v = mulv(correct, {sum[0] * norm, sum[1] * norm, sum[2] * norm});
      float *o = &rgba[(static_cast<size_t>(y) * width + x) * 4];
      o[0] = static_cast<float>(std::max(0.0, v[0]));
      o[1] = static_cast<float>(std::max(0.0, v[1]));
      o[2] = static_cast<float>(std::max(0.0, v[2]));
    }
  return true;
}

float rgba_key(const std::vector<float> &rgba, int width, int height)
{
  constexpr int kCols = 128, kRows = 96;
  if(width < kCols || height < kRows) return 0.0f;
  double log_sum = 0.0, weight_sum = 0.0;
  for(int r = 0; r < kRows; r++)
    for(int c = 0; c < kCols; c++)
    {
      const float *p = &rgba[(static_cast<size_t>(r * height / kRows) * width + c * width / kCols) * 4];
      const double yv = std::max(0.0, 0.2627 * p[0] + 0.6780 * p[1] + 0.0593 * p[2]);
      const double dx = (c + 0.5) / kCols - 0.5, dy = (r + 0.5) / kRows - 0.5;
      const double w = 1.0 + 3.0 * std::exp(-(dx * dx + dy * dy) / (2 * 0.18 * 0.18));
      log_sum += w * std::log(yv + 1e-4);
      weight_sum += w;
    }
  return static_cast<float>(std::exp(log_sum / weight_sum) - 1e-4);
}

float rgba_center_mean(const std::vector<float> &rgba, int width, int height)
{
  if(width <= 0 || height <= 0) return 0.0f;
  const double longest = std::max(width, height);
  double sum = 0.0, weights = 0.0;
  for(int y = 0; y < height; y += 4)
    for(int x = 0; x < width; x += 4)
    {
      const float *p = &rgba[(static_cast<size_t>(y) * width + x) * 4];
      const double dx = (x + 0.5 - width / 2.0) / longest, dy = (y + 0.5 - height / 2.0) / longest;
      const double w = std::exp(-(dx * dx + dy * dy) / (2 * 0.2 * 0.2));
      sum += w * std::max(0.0, 0.2627 * p[0] + 0.6780 * p[1] + 0.0593 * p[2]);
      weights += w;
    }
  return weights > 0 ? static_cast<float>(sum / weights) : 0.0f;
}

bool apply_white(DecodedRaw &d, White white, float kelvin, float tint, std::string &error)
{
  // the DNG reader set the file's own (as shot) white and matrix; a colour temperature goes
  // through the DNG's ColorMatrix as LibRaw read it, as for any raw
  if(d.engine_dng && (white == White::AsShot || !d.has_matrix)) return true;
  if(white == White::AsShot) return set_neutral(d, d.as_shot_neutral, error);
  double x, y;
  kelvin_xy(kelvin, x, y);
  const Vec3 xyz = {x / y, 1.0, (1.0 - x - y) / y};
  Mat3 cm;
  std::copy(std::begin(d.xyz_to_camera), std::end(d.xyz_to_camera), cm.begin());
  const Vec3 cam = mulv(cm, xyz);
  if(!(cam[0] > 0 && cam[1] > 0 && cam[2] > 0))
  {
    error = "色温超出相机色彩矩阵的范围";
    return false;
  }
  // positive tint: a greener light taken out, the picture towards magenta
  double neutral[3] = {cam[0] / cam[1], 1.0, cam[2] / cam[1]};
  neutral[1] *= std::exp(0.25 * std::clamp(tint, -1.0f, 1.0f));
  return set_neutral(d, neutral, error);
}

void estimate_noise(DecodedRaw &d)
{
  if(d.engine_dng && d.raw.noise_scale > 0) return; // the DNG's own NoiseProfile
  const double iso = std::clamp(d.meta.iso > 0 ? d.meta.iso : 100.0f, 50.0f, 204800.0f);
  const double full_well = 50000.0 * 100.0 / iso;      // electrons at white for this ISO
  const double read_e = iso >= 800.0 ? 1.4 : 3.2;      // dual conversion gain from ISO 800
  d.raw.noise_scale = static_cast<float>(1.0 / full_well);
  d.raw.noise_offset = static_cast<float>((read_e / full_well) * (read_e / full_well));
}

std::vector<uint8_t> embedded_preview(const std::string &path, std::string &error)
{
  std::vector<uint8_t> jpeg;
  auto lr = std::make_unique<LibRaw>();
  int r = lr->open_file(path.c_str());
  if(r == LIBRAW_SUCCESS)
  { // the largest JPEG of the file's previews (LibRaw's own pick is not always the full-size one)
    const libraw_thumbnail_list_t &list = lr->imgdata.thumbs_list;
    int best = -1;
    for(int i = 0; i < list.thumbcount && i < LIBRAW_THUMBNAIL_MAXCOUNT; i++)
      if(list.thumblist[i].tformat == LIBRAW_INTERNAL_THUMBNAIL_JPEG &&
         (best < 0 || int(list.thumblist[i].twidth) * list.thumblist[i].theight >
                          int(list.thumblist[best].twidth) * list.thumblist[best].theight))
        best = i;
    r = best >= 0 ? lr->unpack_thumb_ex(best) : lr->unpack_thumb();
  }
  if(r != LIBRAW_SUCCESS)
  {
    error = libraw_strerror(r);
    return jpeg;
  }
  const libraw_thumbnail_t &t = lr->imgdata.thumbnail;
  if(t.tformat != LIBRAW_THUMBNAIL_JPEG || !t.thumb || t.tlength == 0)
  {
    error = "没有内嵌 JPEG 预览";
    return jpeg;
  }
  jpeg.assign(reinterpret_cast<const uint8_t *>(t.thumb), reinterpret_cast<const uint8_t *>(t.thumb) + t.tlength);
  return jpeg;
}

} // namespace camraw
