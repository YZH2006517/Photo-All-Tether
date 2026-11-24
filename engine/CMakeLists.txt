# Film engine of Photo All 0.3.0 (spektrafilm's spectral film / paper model on Vulkan compute,
# vkdt filmsim shaders), copied from engine-port/ (see README.md). Built here in place of the
# git submodule's older engine (third_party/Photo-All-Android/engine, 0.2.2).
#   Android: added from android/app/src/main/cpp/CMakeLists.txt (the NDK provides glslc and the
#            Vulkan headers).
#   Desktop: added from native/desktop/CMakeLists.txt, -DFILMENGINE_NDK=<NDK> for glslc and headers.
#
# Left out on purpose: the engine's own LibRaw reader (camera_raw.cpp; this app decodes camera
# RAWs itself, native/src/camera_raw.cpp, with its own LibRaw) -> stubs/camera_raw_none.cpp.
# libjxl (third_party/libjxl) is compiled in when present: the DNGs LibRaw cannot decode (JPEG XL
# tiles, DNG 1.7) fall back to this engine's DNG reader (native/src/camera_raw.cpp).
cmake_minimum_required(VERSION 3.22)
project(filmengine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID)
  set(FILMENGINE_NDK "${ANDROID_NDK}")
else()
  set(FILMENGINE_NDK "" CACHE PATH "Android NDK, used for glslc and the Vulkan headers")
endif()
file(GLOB _glslc_dirs "${FILMENGINE_NDK}/shader-tools/*")
find_program(GLSLC NAMES glslc HINTS ${_glslc_dirs} "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin" REQUIRED)

# ---- shaders -----------------------------------------------------------------
set(VKDT_SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/vkdt")
set(OWN_SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/own")
set(DENOISE_SHADERS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/denoise")
set(KERNEL_SOURCES "")
foreach(kernel setup dirlut fast expose develop halin halout scatter negprint)
  list(APPEND KERNEL_SOURCES "${VKDT_SHADERS}/filmsim/${kernel}.comp")
endforeach()
foreach(kernel blur downsample decode encode raw fusion chroma flashmix flashfrac flashwb diffpick diffuse finish)
  list(APPEND KERNEL_SOURCES "${OWN_SHADERS}/${kernel}.comp")
endforeach()
foreach(kernel dn_half dn_downcov dn_down dn_assemble dn_doub)
  list(APPEND KERNEL_SOURCES "${DENOISE_SHADERS}/${kernel}.comp")
endforeach()
file(GLOB_RECURSE SHADER_DEPS CONFIGURE_DEPENDS "${VKDT_SHADERS}/*.glsl" "${VKDT_SHADERS}/*.h"
     "${DENOISE_SHADERS}/*.glsl")
set(SPV_DIR "${CMAKE_CURRENT_BINARY_DIR}/spv")
set(SPV_FILES "")
# encode.comp once more into half floats ("encode16", 16-bit / lossless output): listed as
# <source>|<kernel name>|<define>
list(APPEND KERNEL_SOURCES "${OWN_SHADERS}/encode.comp|encode16|-DOUTPUT_HALF")
foreach(entry IN LISTS KERNEL_SOURCES)
  string(REPLACE "|" ";" parts "${entry}")
  list(GET parts 0 source)
  get_filename_component(kernel "${source}" NAME_WE)
  set(define "")
  list(LENGTH parts n)
  if(n GREATER 1)
    list(GET parts 1 kernel)
    list(GET parts 2 define)
  endif()
  get_filename_component(source_dir "${source}" DIRECTORY)
  # every kernel twice: optimised, and an unoptimised "<kernel>_safe" for a GPU driver whose
  # compiler fails on the optimised one (FilmEngine.create(..., safe_shaders = true))
  foreach(variant opt safe)
    if(variant STREQUAL "opt")
      set(spv "${SPV_DIR}/${kernel}.spv")
      set(level -O)
    else()
      set(spv "${SPV_DIR}/${kernel}_safe.spv")
      set(level -O0)
    endif()
    add_custom_command(
      OUTPUT "${spv}"
      COMMAND "${CMAKE_COMMAND}" -E make_directory "${SPV_DIR}"
      COMMAND "${GLSLC}" --target-env=vulkan1.1 ${level} ${define} -I "${VKDT_SHADERS}" -I "${source_dir}" "${source}" -o "${spv}"
      DEPENDS "${source}" ${SHADER_DEPS}
      COMMENT "glslc ${kernel}.comp (${variant})"
      VERBATIM)
    list(APPEND SPV_FILES "${spv}")
  endforeach()
endforeach()
set(SHADER_CPP "${CMAKE_CURRENT_BINARY_DIR}/film_shaders.cpp")
add_custom_command(
  OUTPUT "${SHADER_CPP}"
  COMMAND "${CMAKE_COMMAND}" "-DOUTPUT=${SHADER_CPP}" "-DSPV_FILES=${SPV_FILES}"
          -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
  DEPENDS ${SPV_FILES} "${CMAKE_CURRENT_SOURCE_DIR}/cmake/embed_spirv.cmake"
  COMMENT "embedding SPIR-V"
  VERBATIM)

# ---- libjxl: JPEG XL tiles of DNG 1.7 files (Samsung Expert RAW, newer phones and cameras),
# decoded by src/dng.cpp + jxl_tile.cpp. BSD-3-Clause; Highway Apache-2.0/BSD-3, Brotli MIT,
# skcms BSD-3. Decoder only, from third_party/libjxl (v0.11.2) when it is there; the same
# configuration as the Photo All phone app's build.
set(LIBJXL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libjxl" CACHE PATH "libjxl checkout (decoder only)")
set(FILMENGINE_JXL OFF)
if(EXISTS "${LIBJXL_DIR}/CMakeLists.txt" AND EXISTS "${LIBJXL_DIR}/third_party/highway/CMakeLists.txt")
  set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
  foreach(off JPEGXL_ENABLE_TOOLS JPEGXL_ENABLE_JPEGLI JPEGXL_ENABLE_JPEGLI_LIBJPEG JPEGXL_ENABLE_DOXYGEN
      JPEGXL_ENABLE_MANPAGES JPEGXL_ENABLE_BENCHMARK JPEGXL_ENABLE_EXAMPLES JPEGXL_ENABLE_JNI JPEGXL_ENABLE_SJPEG
      JPEGXL_ENABLE_OPENEXR JPEGXL_ENABLE_TRANSCODE_JPEG JPEGXL_ENABLE_BOXES JPEGXL_ENABLE_FUZZERS JPEGXL_ENABLE_DEVTOOLS
      JPEGXL_ENABLE_VIEWERS JPEGXL_ENABLE_PLUGINS JPEGXL_ENABLE_TCMALLOC JPEGXL_ENABLE_AVX512
      JPEGXL_ENABLE_AVX512_SPR JPEGXL_ENABLE_AVX512_ZEN4 JPEGXL_BUNDLE_LIBPNG JPEGXL_ENABLE_WASM_THREADS
      JPEGXL_ENABLE_JPEGLI_TESTS JPEGXL_INSTALL_JPEGLI_LIBJPEG)
    set(${off} OFF CACHE BOOL "" FORCE)
  endforeach()
  set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE) # colour transforms the decoder needs
  # one SIMD target (the baseline: NEON on arm64), not one copy per CPU feature level
  add_compile_definitions(HWY_COMPILE_ONLY_STATIC=1)
  add_subdirectory("${LIBJXL_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/libjxl" EXCLUDE_FROM_ALL)
  if(NOT MSVC)
    # optimised in every build (a debug build's -O0 makes a 12 MP decode crawl), and small
    foreach(t jxl_dec-obj jxl_threads hwy brotlicommon brotlidec jxl_cms skcms)
      if(TARGET ${t})
        target_compile_options(${t} PRIVATE -O2 -ffunction-sections -fdata-sections)
      endif()
    endforeach()
  endif()
  set(FILMENGINE_JXL ON)
endif()
message(STATUS "filmengine: JPEG XL ${FILMENGINE_JXL}")

# ---- library -----------------------------------------------------------------
add_library(filmengine_core STATIC
  src/burst.cpp
  src/dng.cpp
  src/film_engine.cpp
  src/film_params.cpp
  src/jxl_tile.cpp
  src/vk_api.cpp
  stubs/camera_raw_none.cpp
  "${SHADER_CPP}")
# stubs/ stays private: its camera_raw.h must not shadow the app's own (native/src/camera_raw.h)
target_include_directories(filmengine_core PUBLIC src PRIVATE stubs "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_definitions(filmengine_core PUBLIC VK_NO_PROTOTYPES)
if(FILMENGINE_JXL)
  target_link_libraries(filmengine_core PRIVATE jxl_dec jxl_threads)
  target_compile_definitions(filmengine_core PRIVATE FILMENGINE_JXL=1)
endif()

if(ANDROID)
  target_link_libraries(filmengine_core PUBLIC dl)
else()
  # Only the Vulkan headers are taken from the NDK sysroot; its libc headers must not leak in.
  set(_sysroot_include "")
  file(GLOB _sysroots "${FILMENGINE_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/include")
  list(GET _sysroots 0 _sysroot_include)
  set(VK_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/vulkan-headers")
  file(COPY "${_sysroot_include}/vulkan" "${_sysroot_include}/vk_video" DESTINATION "${VK_INCLUDE}")
  target_include_directories(filmengine_core PUBLIC "${VK_INCLUDE}")
  if(MSVC)
    # C4305: wb.h tables are double literals in float arrays
    target_compile_options(filmengine_core PRIVATE /W3 /utf-8 /wd4305)
    target_compile_definitions(filmengine_core PRIVATE NOMINMAX)
  else()
    target_link_libraries(filmengine_core PUBLIC ${CMAKE_DL_LIBS})
  endif()
endif()
