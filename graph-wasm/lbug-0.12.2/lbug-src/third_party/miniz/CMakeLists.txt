if(POLICY CMP0063)
    cmake_policy(SET CMP0063 NEW)
endif()

add_library(miniz STATIC miniz.cpp)

target_include_directories(
  miniz
  PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)
