# Native parts of Photo All Tether besides the film engine: LibRaw and the camera raw ->
# engine bridge (src/camera_raw). Added after the engine (target filmengine_core) by the
# Android app's CMakeLists and by desktop/CMakeLists.txt.
cmake_minimum_required(VERSION 3.22)

set(LIBRAW_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party/LibRaw")
file(GLOB LIBRAW_SOURCES CONFIGURE_DEPENDS "${LIBRAW_DIR}/src/*.cpp" "${LIBRAW_DIR}/src/*/*.cpp")
# *_ph.cpp are placeholders the other sources include
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")
add_library(libraw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw_static PUBLIC "${LIBRAW_DIR}")
target_compile_definitions(libraw_static PUBLIC LIBRAW_NODLL PRIVATE NO_LCMS NO_JASPER NO_JPEG _CRT_SECURE_NO_WARNINGS)
if(MSVC)
  target_compile_options(libraw_static PRIVATE /w /O2 /utf-8)
  target_link_libraries(libraw_static PUBLIC ws2_32)
else()
  # decoding a 24 MP NEF runs per photo: optimised in debug builds too
  target_compile_options(libraw_static PRIVATE -w -O3)
endif()

add_library(camera_raw STATIC src/camera_raw.cpp)
target_include_directories(camera_raw PUBLIC src)
target_link_libraries(camera_raw PUBLIC filmengine_core libraw_static)
if(MSVC)
  target_compile_options(camera_raw PRIVATE /utf-8 /W3)
  target_compile_definitions(camera_raw PRIVATE NOMINMAX)
else()
  target_compile_options(camera_raw PRIVATE -O3 -Wall)
endif()
