# Native parts of Photo All Tether besides the film engine: LibRaw and the camera raw ->
# engine bridge (src/camera_raw). Added after the engine (target filmengine_core) by the
# Android app's CMakeLists and by desktop/CMakeLists.txt.
cmake_minimum_required(VERSION 3.22)

include(ExternalProject)

# ---- libjpeg-turbo (BSD-3-Clause / IJG / zlib): LibRaw's lossy JPEG DNGs (Adobe DNG Converter's
# "lossy compressed", some cameras and phones) and Kodak's JPEG raws. Its build refuses
# add_subdirectory(), so it is built on its own with this build's toolchain: the static
# library, decoder use only, no SIMD (lossy DNG tiles are small; no assembler needed).
set(LIBJPEG_TURBO_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party/libjpeg-turbo")
set(JPEG_PREFIX "${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo")
if(MSVC)
  set(JPEG_LIB "${JPEG_PREFIX}/lib/jpeg-static.lib")
else()
  set(JPEG_LIB "${JPEG_PREFIX}/lib/libjpeg.a")
endif()
set(JPEG_ARGS
  -DCMAKE_INSTALL_PREFIX=${JPEG_PREFIX} -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_BUILD_TYPE=Release
  -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF -DWITH_SIMD=OFF -DWITH_TOOLS=OFF
  -DWITH_TESTS=OFF -DWITH_FUZZ=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON)
if(ANDROID)
  list(APPEND JPEG_ARGS -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DANDROID_NDK=${ANDROID_NDK} -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
endif()
ExternalProject_Add(libjpeg_turbo_build
  SOURCE_DIR "${LIBJPEG_TURBO_DIR}"
  BINARY_DIR "${JPEG_PREFIX}-build"
  INSTALL_DIR "${JPEG_PREFIX}"
  CMAKE_ARGS ${JPEG_ARGS}
  BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --config Release --target jpeg-static
  INSTALL_COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release --component lib
          COMMAND ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release --component include
  BUILD_BYPRODUCTS "${JPEG_LIB}"
  UPDATE_COMMAND "" UPDATE_DISCONNECTED TRUE)
file(MAKE_DIRECTORY "${JPEG_PREFIX}/include")
add_library(jpeg_static STATIC IMPORTED GLOBAL)
set_target_properties(jpeg_static PROPERTIES IMPORTED_LOCATION "${JPEG_LIB}"
  INTERFACE_INCLUDE_DIRECTORIES "${JPEG_PREFIX}/include")
add_dependencies(jpeg_static libjpeg_turbo_build)

set(LIBRAW_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party/LibRaw")
file(GLOB LIBRAW_SOURCES CONFIGURE_DEPENDS "${LIBRAW_DIR}/src/*.cpp" "${LIBRAW_DIR}/src/*/*.cpp")
# *_ph.cpp are placeholders the other sources include
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")
add_library(libraw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw_static PUBLIC "${LIBRAW_DIR}")
target_compile_definitions(libraw_static PUBLIC LIBRAW_NODLL PRIVATE NO_LCMS NO_JASPER _CRT_SECURE_NO_WARNINGS
  # Sigma's Foveon X3F (SD / DP Merrill and Quattro): LibRaw reads them only with its x3f tools
  USE_X3FTOOLS
  # lossy DNG and Kodak JPEG raws (libjpeg-turbo above)
  USE_JPEG)
target_link_libraries(libraw_static PUBLIC jpeg_static)
add_dependencies(libraw_static libjpeg_turbo_build)
if(ANDROID)
  # Deflate-compressed DNGs (floating point: HDR merges, Adobe DNG Converter, some phones and
  # drones): the NDK's zlib. (The desktop build has none; there such DNGs report unsupported.)
  target_compile_definitions(libraw_static PRIVATE USE_ZLIB)
  target_link_libraries(libraw_static PUBLIC z)
endif()
if(MSVC)
  target_compile_options(libraw_static PRIVATE /w /O2 /utf-8)
  target_link_libraries(libraw_static PUBLIC ws2_32)
else()
  # decoding a 24 MP NEF runs per photo: optimised in debug builds too
  target_compile_options(libraw_static PRIVATE -w -O3)
endif()

add_library(camera_raw STATIC src/camera_raw.cpp src/tiff16.cpp)
target_include_directories(camera_raw PUBLIC src)
target_link_libraries(camera_raw PUBLIC filmengine_core libraw_static)
if(MSVC)
  target_compile_options(camera_raw PRIVATE /utf-8 /W3)
  target_compile_definitions(camera_raw PRIVATE NOMINMAX)
else()
  target_compile_options(camera_raw PRIVATE -O3 -Wall)
endif()
