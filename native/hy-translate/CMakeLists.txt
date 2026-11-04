cmake_minimum_required(VERSION 3.15)
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
project(hy_translate C CXX)

# LLAMA_DIR is llama.cpp at the commit named in scripts/Build-HyTranslate.ps1, with docs/patches/llama-stq-avx2.patch applied.
set(LLAMA_DIR "" CACHE PATH "llama.cpp source directory")
set(CMAKE_CXX_STANDARD 17)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
# Fixed rather than detected, so a build runs on any AVX2 computer and not only on the one that made it.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 ON CACHE BOOL "" FORCE)
set(GGML_FMA ON CACHE BOOL "" FORCE)
set(GGML_F16C ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)
add_executable(hy-translate hy_translate.cpp)
target_link_libraries(hy-translate PRIVATE llama ggml-cpu)
if (MSVC)
    target_compile_options(hy-translate PRIVATE /utf-8)
endif()
