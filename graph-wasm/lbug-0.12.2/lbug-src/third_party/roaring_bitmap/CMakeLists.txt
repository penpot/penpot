if (POLICY CMP0063)
    cmake_policy(SET CMP0063 NEW)
endif ()

add_library(roaring_bitmap STATIC roaring.c)

target_include_directories(
        roaring_bitmap
        PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)
