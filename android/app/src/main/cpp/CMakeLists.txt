cmake_minimum_required(VERSION 3.22)
project(abhify_llm C CXX)
set(CMAKE_CXX_STANDARD 17)

# llama.cpp v0.7.0, pinned by commit and archive SHA-256. Override with
# -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=<checkout of the same commit> to build offline.
include(FetchContent)
FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/620d2225cc436ab0b001e3200e590e3b286ef9ab.tar.gz
    URL_HASH SHA256=1d4a987d9bd048f6e93107e838026438df70fbde237fc0c69c63d600512b4bd9
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

option(ABHIFY_VULKAN "Build the optional Vulkan backend" OFF)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# CPU variants for every ARM feature level, chosen at run time by what the phone's CPU supports
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
endif()
set(GGML_VULKAN ${ABHIFY_VULKAN} CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(abhify_llm SHARED abhify_llm.cpp)
target_link_libraries(abhify_llm llama ggml android log)
