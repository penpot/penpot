add_library(snappy
    STATIC
    snappy.cc
    snappy-sinksource.cc)

target_include_directories(snappy PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)
