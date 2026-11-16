// JNI bridge for org.photoall.tether.engine.TetherNative: the phone app's film engine, fed
// from camera raw files (LibRaw) or from camera JPEGs.
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "camera_raw.h"
#include "film_engine.h"

namespace {

struct Engine
{
  std::unique_ptr<film::FilmEngine> engine;
  film::DevelopResult last;
  std::mutex mutex;
};

struct Raw
{
  camraw::DecodedRaw decoded;
  std::mutex mutex; // the white balance is set on it per develop
};

void throw_java(JNIEnv *env, const std::string &message)
{
  if(env->ExceptionCheck()) return;
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

#define JNI_TRY try {
#define JNI_CATCH(env)                                                                                    \
  }                                                                                                       \
  catch(const std::bad_alloc &) { throw_java(env, "内存不足"); }                                          \
  catch(const std::exception &e) { throw_java(env, e.what()); }                                           \
  catch(...) { throw_java(env, "native error"); }

std::string str(JNIEnv *env, jstring s)
{
  const char *chars = env->GetStringUTFChars(s, nullptr);
  std::string out = chars;
  env->ReleaseStringUTFChars(s, chars);
  return out;
}

bool read_asset(AAssetManager *assets, const char *path, std::vector<uint8_t> &bytes)
{
  AAsset *asset = AAssetManager_open(assets, path, AASSET_MODE_BUFFER);
  if(!asset) return false;
  const off_t size = AAsset_getLength(asset);
  bytes.resize(static_cast<size_t>(size));
  const bool ok = AAsset_read(asset, bytes.data(), bytes.size()) == size;
  AAsset_close(asset);
  return ok;
}

bool load_lut(AAssetManager *assets, const char *path, film::Lut &lut, std::string &error)
{
  std::vector<uint8_t> bytes;
  if(!read_asset(assets, path, bytes))
  {
    error = std::string("missing asset ") + path;
    return false;
  }
  return film::parse_lut(bytes.data(), bytes.size(), lut, error);
}

bool read_params(JNIEnv *env, jobjectArray names, jfloatArray values, film::FilmParams &params, film::Stages &stages)
{
  const jsize count = env->GetArrayLength(names);
  std::vector<float> v(static_cast<size_t>(count));
  env->GetFloatArrayRegion(values, 0, count, v.data());
  for(jsize i = 0; i < count; i++)
  {
    auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    const std::string key = str(env, name);
    env->DeleteLocalRef(name);
    if(!film::set_stage(stages, key, v[i]) && !film::set_param(params, key, v[i]))
    {
      throw_java(env, "unknown film parameter: " + key);
      return false;
    }
  }
  return true;
}

jlongArray result_info(JNIEnv *env, const film::DevelopResult &r, int orientation)
{
  const jlong out[6] = {r.width, r.height, r.passes, static_cast<jlong>(r.peak_image_bytes),
                        static_cast<jlong>(r.gpu_ms * 1000.0), orientation};
  jlongArray result = env->NewLongArray(6);
  env->SetLongArrayRegion(result, 0, 6, out);
  return result;
}

void add_note(std::string &note, const char *section, const char *key, const std::string &value)
{
  note += section;
  note += '\t';
  note += key;
  note += '\t';
  note += value;
  note += '\n';
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_org_photoall_tether_engine_TetherNative_engineCreate(JNIEnv *env, jclass, jobject assets_java)
{
  JNI_TRY
  AAssetManager *assets = AAssetManager_fromJava(env, assets_java);
  film::Lut filmsim, spectra;
  std::string error;
  if(!load_lut(assets, "filmsim/filmsim.lut", filmsim, error) ||
     !load_lut(assets, "filmsim/spectra-em.lut", spectra, error))
  {
    throw_java(env, error);
    return 0;
  }
  auto handle = std::make_unique<Engine>();
  // No HDR+ merge kernels: a camera frame is developed alone (and some Mali drivers crash
  // compiling them).
  handle->engine = film::FilmEngine::create(filmsim, spectra, error, false);
  if(!handle->engine)
  {
    throw_java(env, error);
    return 0;
  }
  return reinterpret_cast<jlong>(handle.release());
  JNI_CATCH(env)
  return 0;
}

JNIEXPORT void JNICALL Java_org_photoall_tether_engine_TetherNative_engineDestroy(JNIEnv *env, jclass, jlong h)
{
  JNI_TRY
  delete reinterpret_cast<Engine *>(h);
  JNI_CATCH(env)
}

JNIEXPORT jstring JNICALL Java_org_photoall_tether_engine_TetherNative_engineDevice(JNIEnv *env, jclass, jlong h)
{
  JNI_TRY
  return env->NewStringUTF(reinterpret_cast<Engine *>(h)->engine->device().name.c_str());
  JNI_CATCH(env)
  return nullptr;
}

JNIEXPORT jobjectArray JNICALL Java_org_photoall_tether_engine_TetherNative_names(JNIEnv *env, jclass, jboolean papers)
{
  JNI_TRY
  const char *const *names = papers ? film::kPapers : film::kFilmStocks;
  const int count = papers ? film::kPaperCount : film::kFilmCount;
  jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
  for(int i = 0; i < count; i++) env->SetObjectArrayElement(out, i, env->NewStringUTF(names[i]));
  return out;
  JNI_CATCH(env)
  return nullptr;
}

// ---- raw files -------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_org_photoall_tether_engine_TetherNative_rawOpen(JNIEnv *env, jclass, jstring path,
                                                                           jint long_edge_hint)
{
  JNI_TRY
  auto raw = std::make_unique<Raw>();
  std::string error;
  camraw::DecodeOptions opts;
  opts.long_edge_hint = long_edge_hint;
  if(!camraw::decode_raw_file(str(env, path), raw->decoded, error, opts))
  {
    throw_java(env, error);
    return 0;
  }
  if(raw->decoded.bayer) camraw::estimate_noise(raw->decoded);
  return reinterpret_cast<jlong>(raw.release());
  JNI_CATCH(env)
  return 0;
}

JNIEXPORT void JNICALL Java_org_photoall_tether_engine_TetherNative_rawRelease(JNIEnv *env, jclass, jlong h)
{
  JNI_TRY
  delete reinterpret_cast<Raw *>(h);
  JNI_CATCH(env)
}

// "key\tvalue" lines: make, model, lens, iso, shutter, aperture, focal, time, width, height, ...
JNIEXPORT jstring JNICALL Java_org_photoall_tether_engine_TetherNative_rawMeta(JNIEnv *env, jclass, jlong h)
{
  JNI_TRY
  const Raw *raw = reinterpret_cast<Raw *>(h);
  const camraw::RawMeta &m = raw->decoded.meta;
  const film::RawImage &r = raw->decoded.raw;
  char line[160];
  std::string out;
  auto put = [&](const char *key, const std::string &value) { out += std::string(key) + "\t" + value + "\n"; };
  put("make", m.make);
  put("model", m.model);
  put("lens", m.lens);
  put("software", m.software);
  std::snprintf(line, sizeof(line), "%.0f", m.iso);
  put("iso", line);
  std::snprintf(line, sizeof(line), "%.8g", m.shutter);
  put("shutter", line);
  std::snprintf(line, sizeof(line), "%.2g", m.aperture);
  put("aperture", line);
  std::snprintf(line, sizeof(line), "%.4g", m.focal);
  put("focal", line);
  put("time", std::to_string(m.timestamp));
  put("width", std::to_string(r.width));
  put("height", std::to_string(r.height));
  put("orientation", std::to_string(r.orientation));
  put("bits", std::to_string(m.bits));
  put("decoder", m.decoder);
  put("bayer", raw->decoded.bayer ? "1" : "0");
  if(!raw->decoded.bayer) put("demosaic", raw->decoded.demosaic);
  std::snprintf(line, sizeof(line), "%.0f %.0f %.0f %.0f / %.0f", r.black[0], r.black[1], r.black[2], r.black[3], r.white);
  put("levels", line);
  const double *n = raw->decoded.as_shot_neutral;
  std::snprintf(line, sizeof(line), "%.4f %.4f %.4f", n[0], n[1], n[2]);
  put("neutral", line);
  return env->NewStringUTF(out.c_str());
  JNI_CATCH(env)
  return nullptr;
}

JNIEXPORT jbyteArray JNICALL Java_org_photoall_tether_engine_TetherNative_embeddedPreview(JNIEnv *env, jclass,
                                                                                        jstring path)
{
  JNI_TRY
  std::string error;
  const std::vector<uint8_t> jpeg = camraw::embedded_preview(str(env, path), error);
  if(jpeg.empty()) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(jpeg.size()));
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(jpeg.size()), reinterpret_cast<const jbyte *>(jpeg.data()));
  return out;
  JNI_CATCH(env)
  return nullptr;
}

// Develops a decoded raw. white: 0 as shot, 1 kelvin (+ tint). match_key > 0: the camera
// JPEG's centre-weighted key, the develop starts from that brightness (as the phone app
// matches its viewfinder). Returns {width, height, passes, peak bytes, gpu us, orientation}.
JNIEXPORT jlongArray JNICALL Java_org_photoall_tether_engine_TetherNative_developRaw(
    JNIEnv *env, jclass, jlong engine_h, jlong raw_h, jobjectArray names, jfloatArray values, jint max_long_edge,
    jfloat exposure_ev, jint seed, jfloat match_key, jint white, jfloat kelvin, jfloat tint, jfloat raw_denoise,
    jfloat chroma_denoise)
{
  JNI_TRY
  Engine *engine = reinterpret_cast<Engine *>(engine_h);
  Raw *raw = reinterpret_cast<Raw *>(raw_h);
  film::FilmParams params;
  film::DevelopOptions options;
  if(!read_params(env, names, values, params, options.stages)) return nullptr;
  options.max_long_edge = max_long_edge;
  options.exposure_ev = exposure_ev;
  options.seed = static_cast<uint32_t>(seed);
  options.match_key = match_key;
  options.raw_denoise = raw_denoise;
  options.chroma_denoise = chroma_denoise;

  std::lock_guard<std::mutex> raw_lock(raw->mutex);
  std::string error;
  const camraw::White mode = white == 1 ? camraw::White::Kelvin : camraw::White::AsShot;
  film::SourceImage source;
  std::vector<float> generic;
  float generic_key = 0.0f;
  if(raw->decoded.bayer)
  {
    if(!camraw::apply_white(raw->decoded, mode, kelvin, tint, error))
    {
      throw_java(env, error);
      return nullptr;
    }
    source.raw = &raw->decoded.raw;
  }
  else
  {
    // demosaiced by LibRaw: linear Rec.2020, brightness matched here (the engine's
    // match_key only reads raw sources)
    int gw = 0, gh = 0;
    if(!camraw::generic_pixels(raw->decoded, max_long_edge, mode, kelvin, tint, generic, gw, gh, error))
    {
      throw_java(env, error);
      return nullptr;
    }
    source.rgba32f = generic.data();
    source.width = gw;
    source.height = gh;
    source.space = film::ColourSpace::LinearRec2020;
    generic_key = camraw::rgba_key(generic, gw, gh);
    if(match_key > 0 && generic_key > 0) options.exposure_ev += std::log2(match_key / generic_key);
    options.match_key = 0;
    options.raw_denoise = 0;
  }
  std::lock_guard<std::mutex> lock(engine->mutex);
  if(!engine->engine->develop(source, params, options, engine->last, error))
  {
    throw_java(env, error);
    return nullptr;
  }
  const camraw::RawMeta &m = raw->decoded.meta;
  const film::RawImage &r = raw->decoded.raw;
  char line[160];
  add_note(engine->last.note, "RAW 数据", "相机", m.make + " " + m.model);
  add_note(engine->last.note, "RAW 数据", "解码", "LibRaw " + m.decoder);
  if(raw->decoded.bayer)
  {
    std::snprintf(line, sizeof(line), "%d×%d，%d 位，黑 %.0f 白 %.0f", r.width, r.height, m.bits, r.black[0], r.white);
    add_note(engine->last.note, "RAW 数据", "传感器", line);
    std::snprintf(line, sizeof(line), "%s，增益 %.3f / %.3f / %.3f", white == 1 ? "按色温" : "相机记录", r.wb[0],
                  r.wb[1], r.wb[2]);
    add_note(engine->last.note, "RAW 数据", "白平衡", line);
    std::snprintf(line, sizeof(line), "%.4f（相机 JPEG %.4f）", film::raw_key(r), match_key);
    add_note(engine->last.note, "RAW 数据", "亮度 key", line);
  }
  else
  {
    add_note(engine->last.note, "RAW 数据", "去马赛克", raw->decoded.demosaic);
    std::snprintf(line, sizeof(line), "%d×%d，%d 位", raw->decoded.rgb_width, raw->decoded.rgb_height, m.bits);
    add_note(engine->last.note, "RAW 数据", "传感器", line);
    add_note(engine->last.note, "RAW 数据", "白平衡", white == 1 ? "按色温（相机空间重新配平）" : "相机记录");
    std::snprintf(line, sizeof(line), "%.4f（相机 JPEG %.4f）", generic_key, match_key);
    add_note(engine->last.note, "RAW 数据", "亮度 key", line);
  }
  return result_info(env, engine->last, r.orientation);
  JNI_CATCH(env)
  return nullptr;
}

// Develops an 8-bit picture (a camera JPEG). restore 0..1 takes the camera's rendering off first.
JNIEXPORT jlongArray JNICALL Java_org_photoall_tether_engine_TetherNative_developBitmap(
    JNIEnv *env, jclass, jlong engine_h, jobject bitmap, jobjectArray names, jfloatArray values, jint max_long_edge,
    jfloat exposure_ev, jint seed, jfloat restore)
{
  JNI_TRY
  Engine *engine = reinterpret_cast<Engine *>(engine_h);
  film::FilmParams params;
  film::DevelopOptions options;
  if(!read_params(env, names, values, params, options.stages)) return nullptr;
  options.max_long_edge = max_long_edge;
  options.exposure_ev = exposure_ev;
  options.seed = static_cast<uint32_t>(seed);
  options.stages.restore = restore;

  AndroidBitmapInfo info;
  void *pixels = nullptr;
  if(AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
     info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0)
  {
    throw_java(env, "source must be an ARGB_8888 bitmap");
    return nullptr;
  }
  std::vector<uint8_t> packed(static_cast<size_t>(info.width) * info.height * 4);
  for(uint32_t y = 0; y < info.height; y++)
    std::memcpy(&packed[static_cast<size_t>(y) * info.width * 4], static_cast<uint8_t *>(pixels) + y * info.stride,
                info.width * 4);
  AndroidBitmap_unlockPixels(env, bitmap);
  film::SourceImage source;
  source.width = static_cast<int>(info.width);
  source.height = static_cast<int>(info.height);
  source.rgba8 = packed.data();
  std::string error;
  std::lock_guard<std::mutex> lock(engine->mutex);
  if(!engine->engine->develop(source, params, options, engine->last, error))
  {
    throw_java(env, error);
    return nullptr;
  }
  return result_info(env, engine->last, 1);
  JNI_CATCH(env)
  return nullptr;
}

JNIEXPORT void JNICALL Java_org_photoall_tether_engine_TetherNative_copyResult(JNIEnv *env, jclass, jlong engine_h,
                                                                            jobject bitmap)
{
  JNI_TRY
  Engine *engine = reinterpret_cast<Engine *>(engine_h);
  std::lock_guard<std::mutex> lock(engine->mutex);
  const film::DevelopResult &last = engine->last;
  AndroidBitmapInfo info;
  void *pixels = nullptr;
  if(AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
     info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || static_cast<int>(info.width) != last.width ||
     static_cast<int>(info.height) != last.height || AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0)
  {
    throw_java(env, "result bitmap must be ARGB_8888 at the developed size");
    return;
  }
  for(int y = 0; y < last.height; y++)
    std::memcpy(static_cast<uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride,
                &last.rgba8[static_cast<size_t>(y) * last.width * 4], static_cast<size_t>(last.width) * 4);
  AndroidBitmap_unlockPixels(env, bitmap);
  JNI_CATCH(env)
}

JNIEXPORT jstring JNICALL Java_org_photoall_tether_engine_TetherNative_lastNote(JNIEnv *env, jclass, jlong engine_h)
{
  JNI_TRY
  Engine *engine = reinterpret_cast<Engine *>(engine_h);
  std::lock_guard<std::mutex> lock(engine->mutex);
  return env->NewStringUTF(engine->last.note.c_str());
  JNI_CATCH(env)
  return nullptr;
}

JNIEXPORT void JNICALL Java_org_photoall_tether_engine_TetherNative_setCreativeLut(JNIEnv *env, jclass, jlong engine_h,
                                                                                jbyteArray rgb, jint size,
                                                                                jfloat strength)
{
  JNI_TRY
  Engine *engine = reinterpret_cast<Engine *>(engine_h);
  std::vector<uint8_t> table;
  if(rgb)
  {
    table.resize(static_cast<size_t>(env->GetArrayLength(rgb)));
    env->GetByteArrayRegion(rgb, 0, static_cast<jsize>(table.size()), reinterpret_cast<jbyte *>(table.data()));
    if(table.size() != static_cast<size_t>(size) * size * size * 3)
    {
      throw_java(env, "creative LUT size mismatch");
      return;
    }
  }
  std::string error;
  std::lock_guard<std::mutex> lock(engine->mutex);
  if(!engine->engine->set_creative_lut(rgb ? table.data() : nullptr, size, strength, error)) throw_java(env, error);
  JNI_CATCH(env)
}

} // extern "C"
