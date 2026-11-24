cmake_minimum_required(VERSION 3.22)
project(tetherapp CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The film engine (Photo All 0.3.0's, copied into engine/; see engine/README.md).
set(FILMENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../engine" CACHE PATH "film engine sources")
add_subdirectory("${FILMENGINE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/engine")
# CPU steps (staging, keys) run per photo: optimised in debug builds too
target_compile_options(filmengine_core PRIVATE -O3)

# LibRaw and the camera raw -> engine bridge
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native" "${CMAKE_CURRENT_BINARY_DIR}/native")

add_library(tetherjni SHARED tether_jni.cpp)
target_compile_options(tetherjni PRIVATE -O3)
target_link_libraries(tetherjni PRIVATE camera_raw filmengine_core android jnigraphics log)
