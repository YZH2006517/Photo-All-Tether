// A 16-bit RGB TIFF of a develop: the lossless master saved beside the JPEG.
#pragma once
#include <cstdint>
#include <string>

namespace tiff16 {

struct Meta
{
  std::string make, model, software, description;
  std::string date_time; // "YYYY:MM:DD HH:MM:SS", or empty
};

// Writes [rgba] (width x height, RGBA, 16 bits per channel, display-encoded) as a baseline
// TIFF: RGB, 16 bits per sample, Deflate with horizontal prediction where zlib is built in
// (Android), uncompressed otherwise; the ICC profile of sRGB or Display P3 embedded; turned
// upright by [orientation] (TIFF / EXIF 1, 3, 6, 8: the pixels are rotated, the tag says 1).
bool write(const std::string &path, const uint16_t *rgba, int width, int height, int orientation, bool display_p3,
           const Meta &meta, std::string &error);

// The ICC profile (v2, matrix / TRC) of sRGB or Display P3, as written into the TIFF.
std::string icc_profile(bool display_p3);

} // namespace tiff16
