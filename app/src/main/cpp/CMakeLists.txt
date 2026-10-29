cmake_minimum_required(VERSION 3.22.1)
project(dropthink LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Always optimize llama.cpp, even for Gradle debug variants (a -O0 inference engine is unusably slow).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
  message(FATAL_ERROR "llama.cpp sources not found at ${LLAMA_DIR}. Run scripts/fetch-llama.sh first (it checks out the tag pinned in llama.cpp.version).")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(dropthink SHARED llama_jni.cpp)
target_link_libraries(dropthink PRIVATE llama)
if(ANDROID)
  target_link_libraries(dropthink PRIVATE log android)
  target_link_options(dropthink PRIVATE -Wl,-z,max-page-size=16384)
endif()
