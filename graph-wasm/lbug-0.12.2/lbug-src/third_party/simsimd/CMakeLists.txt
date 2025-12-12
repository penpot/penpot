add_library(simsimd
        STATIC
        lib.c)

target_include_directories(simsimd PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR})

if(NOT MSVC)
    target_compile_options(simsimd PRIVATE -w)
elseif (MSVC)
    target_compile_options(simsimd PRIVATE /w)
endif()
