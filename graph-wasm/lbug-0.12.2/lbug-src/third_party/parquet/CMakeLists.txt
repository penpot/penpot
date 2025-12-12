add_library(parquet
        STATIC
        parquet_constants.cpp
        parquet_types.cpp)

target_include_directories(parquet PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)

target_compile_definitions(parquet PUBLIC HAVE_STDINT_H)
