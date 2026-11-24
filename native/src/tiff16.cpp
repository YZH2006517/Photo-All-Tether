#include "tiff16.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#if defined(__ANDROID__)
#include <zlib.h>
#define TIFF16_DEFLATE 1
#else
#define TIFF16_DEFLATE 0
#endif

namespace tiff16 {
namespace {

using Mat3 = std::array<double, 9>;

Mat3 mul(const Mat3 &a, const Mat3 &b)
{
  Mat3 r{};
  for(int i = 0; i < 3; i++)
    for(int j = 0; j < 3; j++)
      for(int k = 0; k < 3; k++) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
  return r;
}

Mat3 inverse(const Mat3 &m)
{
  const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                     m[2] * (m[3] * m[7] - m[4] * m[6]);
  const double d = 1.0 / det;
  return {(m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
          (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
          (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d};
}

// RGB -> XYZ for primaries (x, y) and white (x, y).
Mat3 rgb_to_xyz(const double p[6], double wx, double wy)
{
  Mat3 m{};
  for(int c = 0; c < 3; c++)
  {
    const double x = p[c * 2], y = p[c * 2 + 1];
    m[0 * 3 + c] = x / y;
    m[1 * 3 + c] = 1.0;
    m[2 * 3 + c] = (1.0 - x - y) / y;
  }
  const Mat3 inv = inverse(m);
  const double W[3] = {wx / wy, 1.0, (1.0 - wx - wy) / wy};
  double s[3];
  for(int i = 0; i < 3; i++) s[i] = inv[i * 3] * W[0] + inv[i * 3 + 1] * W[1] + inv[i * 3 + 2] * W[2];
  for(int r = 0; r < 3; r++)
    for(int c = 0; c < 3; c++) m[r * 3 + c] *= s[c];
  return m;
}

void be32(std::string &s, uint32_t v)
{
  for(int i = 3; i >= 0; i--) s += static_cast<char>((v >> (i * 8)) & 0xFF);
}
void be16(std::string &s, uint16_t v)
{
  s += static_cast<char>(v >> 8);
  s += static_cast<char>(v & 0xFF);
}
void s15(std::string &s, double v) { be32(s, static_cast<uint32_t>(static_cast<int32_t>(std::lround(v * 65536.0)))); }
void pad4(std::string &s)
{
  while(s.size() % 4) s += '\0';
}

// little-endian TIFF writing
struct Out
{
  FILE *f = nullptr;
  bool ok = true;
  void bytes(const void *p, size_t n) { ok = ok && std::fwrite(p, 1, n, f) == n; }
  void u16(uint16_t v) { bytes(&v, 2); }
  void u32(uint32_t v) { bytes(&v, 4); }
  long tell() { return std::ftell(f); }
};

} // namespace

std::string icc_profile(bool p3)
{
  const double srgb[6] = {0.64, 0.33, 0.30, 0.60, 0.15, 0.06};
  const double dp3[6] = {0.680, 0.320, 0.265, 0.690, 0.150, 0.060};
  const Mat3 m = rgb_to_xyz(p3 ? dp3 : srgb, 0.3127, 0.3290);
  // Bradford D65 -> D50 (the PCS white), as ICC profiles carry their colorants
  const Mat3 B = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
  const double d65[3] = {0.95047, 1.0, 1.08883}, d50[3] = {0.96422, 1.0, 0.82521};
  double a[3], b[3];
  for(int i = 0; i < 3; i++)
  {
    a[i] = B[i * 3] * d65[0] + B[i * 3 + 1] * d65[1] + B[i * 3 + 2] * d65[2];
    b[i] = B[i * 3] * d50[0] + B[i * 3 + 1] * d50[1] + B[i * 3 + 2] * d50[2];
  }
  const Mat3 scale = {b[0] / a[0], 0, 0, 0, b[1] / a[1], 0, 0, 0, b[2] / a[2]};
  const Mat3 adapted = mul(mul(inverse(B), mul(scale, B)), m);

  const std::string name = p3 ? "Display P3" : "sRGB IEC61966-2.1";
  // tag data
  std::string desc = "desc";
  be32(desc, 0);
  be32(desc, static_cast<uint32_t>(name.size() + 1));
  desc += name;
  desc += '\0';
  be32(desc, 0); // unicode language
  be32(desc, 0); // unicode count
  be16(desc, 0); // scriptcode code
  desc += '\0';  // scriptcode count
  desc += std::string(67, '\0');
  pad4(desc);
  std::string cprt = "text";
  be32(cprt, 0);
  cprt += "No copyright, use freely";
  cprt += '\0';
  pad4(cprt);
  auto xyz = [](double x, double y, double z) {
    std::string s = "XYZ ";
    be32(s, 0);
    s15(s, x);
    s15(s, y);
    s15(s, z);
    return s;
  };
  const std::string wtpt = xyz(d50[0], d50[1], d50[2]);
  const std::string r = xyz(adapted[0], adapted[3], adapted[6]);
  const std::string g = xyz(adapted[1], adapted[4], adapted[7]);
  const std::string bl = xyz(adapted[2], adapted[5], adapted[8]);
  std::string trc = "curv"; // the sRGB curve (Display P3 shares it), tabulated
  be32(trc, 0);
  const int n = 1024;
  be32(trc, n);
  for(int i = 0; i < n; i++)
  {
    const double v = i / double(n - 1);
    const double l = v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
    be16(trc, static_cast<uint16_t>(std::lround(l * 65535.0)));
  }
  pad4(trc);

  struct Tag { const char *sig; const std::string *data; };
  const Tag tags[] = {{"desc", &desc}, {"cprt", &cprt}, {"wtpt", &wtpt}, {"rXYZ", &r}, {"gXYZ", &g},
                      {"bXYZ", &bl}, {"rTRC", &trc}, {"gTRC", &trc}, {"bTRC", &trc}};
  const int count = sizeof(tags) / sizeof(tags[0]);
  std::string table, data;
  const uint32_t data_start = 128 + 4 + count * 12;
  uint32_t trc_offset = 0;
  for(const Tag &t : tags)
  {
    uint32_t offset;
    if(t.data == &trc && trc_offset) offset = trc_offset; // the three TRCs share one curve
    else
    {
      offset = data_start + static_cast<uint32_t>(data.size());
      if(t.data == &trc) trc_offset = offset;
      data += *t.data;
    }
    table += t.sig;
    be32(table, offset);
    be32(table, static_cast<uint32_t>(t.data == &desc ? 12 + name.size() + 1 + 8 + 3 + 67 : t.data->size()));
  }
  std::string head;
  const uint32_t size = 128 + 4 + count * 12 + static_cast<uint32_t>(data.size());
  be32(head, size);
  be32(head, 0);          // preferred CMM
  be32(head, 0x02100000); // version 2.1
  head += "mntrRGB XYZ ";
  for(int i = 0; i < 6; i++) be16(head, i == 0 ? 2026 : 1); // date
  head += "acsp";
  be32(head, 0);         // platform
  be32(head, 0);         // flags
  be32(head, 0);         // manufacturer
  be32(head, 0);         // model
  be32(head, 0); be32(head, 0); // attributes
  be32(head, 0);         // intent: perceptual
  s15(head, d50[0]); s15(head, d50[1]); s15(head, d50[2]);
  be32(head, 0);         // creator
  head += std::string(128 - head.size(), '\0');
  std::string out = head;
  be32(out, count);
  out += table;
  out += data;
  return out;
}

bool write(const std::string &path, const uint16_t *rgba, int w, int h, int orientation, bool p3, const Meta &meta,
           std::string &error)
{
  if(!rgba || w <= 0 || h <= 0)
  {
    error = "没有 16 位数据";
    return false;
  }
  const bool turn = orientation == 6 || orientation == 8;
  const int ow = turn ? h : w, oh = turn ? w : h;
  // source pixel for output (x, y), rotated clockwise by 90 (6), 180 (3), 270 (8)
  auto src = [&](int x, int y) -> const uint16_t * {
    int sx = x, sy = y;
    switch(orientation)
    {
    case 6: sx = y; sy = h - 1 - x; break;
    case 3: sx = w - 1 - x; sy = h - 1 - y; break;
    case 8: sx = w - 1 - y; sy = x; break;
    default: break;
    }
    return rgba + (static_cast<size_t>(sy) * w + sx) * 4;
  };

  Out o;
  o.f = std::fopen(path.c_str(), "wb");
  if(!o.f)
  {
    error = "无法创建 " + path;
    return false;
  }
  // header; the IFD's offset patched in at the end
  o.bytes("II", 2);
  o.u16(42);
  o.u32(0);
  const int rows_per_strip = 32;
  const int strips = (oh + rows_per_strip - 1) / rows_per_strip;
  std::vector<uint32_t> offsets(strips), counts(strips);
  std::vector<uint16_t> row(static_cast<size_t>(ow) * 3);
  std::vector<uint8_t> raw;
#if TIFF16_DEFLATE
  std::vector<uint8_t> packed;
#endif
  for(int s = 0; s < strips && o.ok; s++)
  {
    const int y0 = s * rows_per_strip, y1 = std::min(oh, y0 + rows_per_strip);
    raw.resize(static_cast<size_t>(y1 - y0) * ow * 6);
    uint8_t *dst = raw.data();
    for(int y = y0; y < y1; y++)
    {
      for(int x = 0; x < ow; x++)
      {
        const uint16_t *p = src(x, y);
        row[x * 3] = p[0];
        row[x * 3 + 1] = p[1];
        row[x * 3 + 2] = p[2];
      }
#if TIFF16_DEFLATE
      // horizontal prediction (TIFF Predictor 2): each sample less its left neighbour's
      for(int x = ow - 1; x > 0; x--)
        for(int c = 0; c < 3; c++) row[x * 3 + c] = static_cast<uint16_t>(row[x * 3 + c] - row[(x - 1) * 3 + c]);
#endif
      std::memcpy(dst, row.data(), row.size() * 2); // little-endian host, as the file
      dst += row.size() * 2;
    }
    offsets[s] = static_cast<uint32_t>(o.tell());
#if TIFF16_DEFLATE
    uLongf n = compressBound(static_cast<uLong>(raw.size()));
    packed.resize(n);
    if(compress2(packed.data(), &n, raw.data(), static_cast<uLong>(raw.size()), 2) != Z_OK)
    {
      o.ok = false;
      break;
    }
    o.bytes(packed.data(), n);
    counts[s] = static_cast<uint32_t>(n);
#else
    o.bytes(raw.data(), raw.size());
    counts[s] = static_cast<uint32_t>(raw.size());
#endif
  }

  // the values that do not fit in an entry, then the IFD
  auto extra_at = [&](const void *p, size_t n) -> uint32_t {
    if(o.tell() % 2) o.bytes("", 1);
    const uint32_t at = static_cast<uint32_t>(o.tell());
    o.bytes(p, n);
    return at;
  };
  const uint16_t bits[3] = {16, 16, 16};
  const uint16_t format[3] = {1, 1, 1};
  const uint32_t res[2] = {300, 1};
  const uint32_t at_bits = extra_at(bits, sizeof(bits));
  const uint32_t at_format = extra_at(format, sizeof(format));
  const uint32_t at_res = extra_at(res, sizeof(res));
  const uint32_t at_offsets = strips > 1 ? extra_at(offsets.data(), offsets.size() * 4) : offsets[0];
  const uint32_t at_counts = strips > 1 ? extra_at(counts.data(), counts.size() * 4) : counts[0];
  struct Text { uint16_t tag; std::string s; uint32_t at = 0; };
  std::vector<Text> texts;
  auto text = [&](uint16_t tag, const std::string &s) {
    if(!s.empty()) texts.push_back({tag, s + '\0'});
  };
  text(270, meta.description);
  text(271, meta.make);
  text(272, meta.model);
  text(305, meta.software);
  if(meta.date_time.size() == 19) text(306, meta.date_time);
  for(Text &t : texts) t.at = t.s.size() > 4 ? extra_at(t.s.data(), t.s.size()) : 0;
  const std::string icc = icc_profile(p3);
  const uint32_t at_icc = extra_at(icc.data(), icc.size());

  struct Entry { uint16_t tag, type; uint32_t count, value; };
  std::vector<Entry> e = {
      {256, 4, 1, static_cast<uint32_t>(ow)},
      {257, 4, 1, static_cast<uint32_t>(oh)},
      {258, 3, 3, at_bits},
      {259, 3, 1, TIFF16_DEFLATE ? 8u : 1u},
      {262, 3, 1, 2},
      {273, 4, static_cast<uint32_t>(strips), at_offsets},
      {274, 3, 1, 1},
      {277, 3, 1, 3},
      {278, 4, 1, static_cast<uint32_t>(rows_per_strip)},
      {279, 4, static_cast<uint32_t>(strips), at_counts},
      {282, 5, 1, at_res},
      {283, 5, 1, at_res},
      {284, 3, 1, 1},
      {296, 3, 1, 2},
      {339, 3, 3, at_format},
      {34675, 7, static_cast<uint32_t>(icc.size()), at_icc},
  };
  if(TIFF16_DEFLATE) e.push_back({317, 3, 1, 2});
  for(const Text &t : texts)
  {
    uint32_t v = t.at;
    if(t.s.size() <= 4) { v = 0; std::memcpy(&v, t.s.data(), t.s.size()); }
    e.push_back({t.tag, 2, static_cast<uint32_t>(t.s.size()), v});
  }
  std::sort(e.begin(), e.end(), [](const Entry &a, const Entry &b) { return a.tag < b.tag; });
  if(o.tell() % 2) o.bytes("", 1);
  const uint32_t ifd = static_cast<uint32_t>(o.tell());
  o.u16(static_cast<uint16_t>(e.size()));
  for(const Entry &x : e)
  {
    o.u16(x.tag);
    o.u16(x.type);
    o.u32(x.count);
    // a single SHORT sits in the entry's first two bytes
    if(x.type == 3 && x.count == 1) { o.u16(static_cast<uint16_t>(x.value)); o.u16(0); }
    else o.u32(x.value);
  }
  o.u32(0);
  std::fseek(o.f, 4, SEEK_SET);
  o.u32(ifd);
  const bool ok = o.ok;
  std::fclose(o.f);
  if(!ok)
  {
    std::remove(path.c_str());
    error = "写入 TIFF 失败（存储空间不足？）";
  }
  return ok;
}

} // namespace tiff16
