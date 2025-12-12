if(POLICY CMP0063)
    cmake_policy(SET CMP0063 NEW)
endif()

add_library(lz4 STATIC lz4.cpp)

target_include_directories(
  lz4
  PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)
