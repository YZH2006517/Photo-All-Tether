# Desktop test build: the app's film engine plus LibRaw and camera_raw, and the
# nef_develop tool. Windows: run inside vcvars64.bat.
#   cmake -S native/desktop -B build/desktop -G Ninja -DFILMENGINE_NDK=<NDK>
#   cmake --build build/desktop
cmake_minimum_required(VERSION 3.22)
project(photoall_tether_desktop CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The app's film engine (engine/). -DFILMENGINE_DIR=third_party/Photo-All-Android/engine builds
# the submodule's older one instead (to compare the two).
set(FILMENGINE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../engine" CACHE PATH "film engine sources")
add_subdirectory("${FILMENGINE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/engine")
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/.." "${CMAKE_CURRENT_BINARY_DIR}/native")

add_executable(nef_develop nef_develop.cpp)
target_link_libraries(nef_develop PRIVATE camera_raw)
if(MSVC)
  target_compile_options(nef_develop PRIVATE /utf-8)
endif()
