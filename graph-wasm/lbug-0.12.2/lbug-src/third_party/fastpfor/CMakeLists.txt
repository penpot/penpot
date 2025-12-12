add_library(fastpfor
        STATIC
        fastpfor/bitpacking.cpp)

target_include_directories(fastpfor PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR})
