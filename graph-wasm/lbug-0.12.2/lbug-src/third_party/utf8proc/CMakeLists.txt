add_library(utf8proc
        STATIC
        utf8proc.cpp
        utf8proc_wrapper.cpp)

target_include_directories(utf8proc PUBLIC ./include)
