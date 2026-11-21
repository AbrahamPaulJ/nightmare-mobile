# libnmvlm.so: llama.cpp + mtmd + ggml (CPU and Hexagon) linked STATICALLY into our JNI bridge (nmvlm.cpp).
# Built only by .github/workflows/vlm-engine.yml, inside llama.cpp's Snapdragon toolchain container, with
# LLAMA_SRC pointing at a pinned llama.cpp checkout. One .so to download and System.load, plus the DSP skels.
cmake_minimum_required(VERSION 3.22)
project(nmvlm C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)   # mtmd's vendor deps (stb, miniaudio) come in with it
set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)    # mtmd lives under tools/
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# ⚠ OpenCL off: ggml-opencl links libOpenCL.so, which some ROMs do not expose to apps, and the NPU is the point.
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} llama.cpp)

add_library(nmvlm SHARED nmvlm.cpp)
target_link_libraries(nmvlm PRIVATE mtmd llama ggml log)
target_compile_features(nmvlm PRIVATE cxx_std_17)
# Only the JNI entry points are exported; everything llama.cpp is internal to this .so.
set_target_properties(nmvlm PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(nmvlm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
