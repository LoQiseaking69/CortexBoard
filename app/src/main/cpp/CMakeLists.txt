cmake_minimum_required(VERSION 3.22)
project("cortex" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_TAG)
    set(LLAMA_TAG "b11549")
endif()

# Prefer a pre-cloned llama.cpp (CI caches it); otherwise fetch the pinned tag.
if(DEFINED LLAMA_SRC_DIR AND EXISTS "${LLAMA_SRC_DIR}/CMakeLists.txt")
    message(STATUS "cortex: using llama.cpp from ${LLAMA_SRC_DIR}")
    add_subdirectory(${LLAMA_SRC_DIR} build-llama)
else()
    message(STATUS "cortex: fetching llama.cpp ${LLAMA_TAG}")
    include(FetchContent)
    FetchContent_Declare(llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${LLAMA_TAG}
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(llama)
endif()

add_library(cortex SHARED cortex_jni.cpp)
target_link_libraries(cortex PRIVATE llama ggml android log)
