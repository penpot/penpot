cmake_minimum_required(VERSION 3.11)

if(POLICY CMP0048)
    cmake_policy(SET CMP0048 NEW)
endif()

if(POLICY CMP0063)
    cmake_policy(SET CMP0063 NEW)
endif()

project(zstd CXX)

add_library(zstd STATIC
        common/entropy_common.cpp
        common/error_private.cpp
        common/fse_decompress.cpp
        common/xxhash.cpp
        common/zstd_common.cpp
        compress/fse_compress.cpp
        compress/hist.cpp
        compress/huf_compress.cpp
        compress/zstd_compress.cpp
        compress/zstd_compress_literals.cpp
        compress/zstd_compress_sequences.cpp
        compress/zstd_compress_superblock.cpp
        compress/zstd_double_fast.cpp
        compress/zstd_fast.cpp
        compress/zstd_lazy.cpp
        compress/zstd_ldm.cpp
        compress/zstd_opt.cpp
        decompress/huf_decompress.cpp
        decompress/zstd_ddict.cpp
        decompress/zstd_decompress.cpp
        decompress/zstd_decompress_block.cpp)

# Hide all zstd symbols, since we are importing the library.
add_compile_definitions(zstd ZSTDLIB_VISIBILITY= ZSTDERRORLIB_VISIBILITY=)

target_include_directories(
        zstd
        PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>)
