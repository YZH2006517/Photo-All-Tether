// Camera raw files (Nikon NEF first, any Bayer raw LibRaw reads) turned into the film
// engine's RawImage: the visible Bayer mosaic, black and white levels, the white balance
// and the white-balanced camera RGB -> linear Rec.2020 matrix, as engine/src/dng.cpp
// produces them from a DNG.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "dng.h" // film::RawImage

namespace camraw {

// What the file says about the shot (EXIF and maker notes, as LibRaw reads them).
struct RawMeta
{
  std::string make, model, lens, software;
  float iso = 0, shutter = 0, aperture = 0, focal = 0; // shutter in seconds
  int64_t timestamp = 0;                                // seconds since 1970, camera clock
  uint32_t shot_order = 0;
  int raw_width = 0, raw_height = 0;                    // the full sensor readout
  int bits = 14;
  std::string decoder;                                  // LibRaw's unpack function
};

// White balance for the develop (declared before DecodedRaw's helpers use it).
enum class White
{
  AsShot = 0,   // the camera's own (as-shot multipliers in the file)
  Kelvin = 1,   // a fixed colour temperature (and tint) through the colour matrix
};

struct DecodedRaw
{
  film::RawImage raw;
  RawMeta meta;
  double xyz_to_camera[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1}; // Adobe ColorMatrix (D65), rows R G B
  bool has_matrix = false;
  double as_shot_neutral[3] = {1, 1, 1};                  // camera RGB of a neutral, green = 1
  double daylight_neutral[3] = {1, 1, 1};                 // the same for D65 (pre_mul)

  // Sensors the engine cannot demosaic itself (Fujifilm X-Trans, Foveon, four-colour, linear
  // DNG, Sony YCC): LibRaw demosaics them to linear Rec.2020, 16-bit RGB, upright, white
  // balanced as shot. raw.cfa is then empty and [bayer] false.
  bool bayer = true;
  int rgb_width = 0, rgb_height = 0;
  std::vector<uint16_t> rgb;
  double rec2020_from_camera[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1}; // balanced camera -> Rec.2020 (LibRaw's)
  std::string demosaic;                                         // how it was demosaiced
};

struct DecodeOptions
{
  // The long edge the photo will be developed at (0 unknown): a non-Bayer sensor at least
  // twice as large is demosaiced at half size (a 40 MP X-Trans file needs ~1 GB otherwise).
  int long_edge_hint = 0;
  bool force_generic = false; // testing: a Bayer file through the generic path
};

// Decodes a raw file (path, or the file's bytes in memory). On success the white balance
// is the camera's as-shot one; apply_white() changes it.
bool decode_raw_file(const std::string &path, DecodedRaw &out, std::string &error, const DecodeOptions &opts = {});
bool decode_raw_buffer(const uint8_t *data, size_t size, DecodedRaw &out, std::string &error,
                       const DecodeOptions &opts = {});

// The picture of a non-Bayer decode as linear Rec.2020 RGBA floats for the engine, box-filtered
// down to at most [max_long_edge], under [white] (as shot, or a colour temperature).
bool generic_pixels(const DecodedRaw &decoded, int max_long_edge, White white, float kelvin, float tint,
                    std::vector<float> &rgba, int &width, int &height, std::string &error);

// Centre-weighted log-average luminance of linear Rec.2020 RGBA (as the engine's raw_key).
float rgba_key(const std::vector<float> &rgba, int width, int height);

// Recomputes raw.wb and raw.camera_to_rec2020 for the white balance. [kelvin] 2000..12000
// and [tint] -1..1 (green-magenta, Duv about +-0.02) are used for White::Kelvin only.
bool apply_white(DecodedRaw &decoded, White white, float kelvin, float tint, std::string &error);

// An approximate sensor noise model for the engine's raw denoise, as a DNG NoiseProfile
// would give it (variance = scale * x + offset, x normalised to white). NEFs carry none;
// this one follows a full-frame BSI sensor of about 24 MP by ISO.
void estimate_noise(DecodedRaw &decoded);

// The largest JPEG preview embedded in a raw file (NEF: the full-size one), or empty.
std::vector<uint8_t> embedded_preview(const std::string &path, std::string &error);

} // namespace camraw
