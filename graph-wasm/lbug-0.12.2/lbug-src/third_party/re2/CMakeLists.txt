# Copyright 2015 The RE2 Authors.  All Rights Reserved. Use of this source code
# is governed by a BSD-style license that can be found in the LICENSE file.

# Old enough to support Ubuntu Trusty.
cmake_minimum_required(VERSION 3.15)

if(POLICY CMP0048)
    cmake_policy(SET CMP0048 NEW)
endif()

if(POLICY CMP0063)
    cmake_policy(SET CMP0063 NEW)
endif()

if(POLICY CMP0069)
    cmake_policy(SET CMP0069 NEW)
endif()

project(RE2 CXX)

set(CMAKE_CXX_VISIBILITY_PRESET hidden)

include(CTest)

# CMake seems to have no way to enable/disable testing per subproject, so we
# provide an option similar to BUILD_TESTING, but just for RE2.
option(RE2_BUILD_TESTING "enable testing for RE2" OFF)

set(EXTRA_TARGET_LINK_LIBRARIES)

if(CMAKE_CXX_COMPILER_ID MATCHES "MSVC")
    if(MSVC_VERSION LESS 1900)
        message(FATAL_ERROR "you need Visual Studio 2015 or later")
    endif()
    if(BUILD_SHARED_LIBS)
        # See http://www.kitware.com/blog/home/post/939 for details.
        cmake_minimum_required(VERSION 3.4)
        set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS ON)
    endif()
    # CMake defaults to /W3, but some users like /W4 (or /Wall) and /WX, so we
    # disable various warnings that aren't particularly helpful.
    add_compile_options(/wd4100
            /wd4201
            /wd4456
            /wd4457
            /wd4702
            /wd4815)
    # Without a byte order mark (BOM), Visual Studio assumes that the source file
    # is encoded using the current user code page, so we specify UTF-8.
    add_compile_options(/utf-8)
elseif(CYGWIN OR MINGW)
    # See https://stackoverflow.com/questions/38139631 for details.
    add_compile_options(-std=gnu++11)
elseif(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-std=c++17)
endif()

add_compile_options(-std=c++17)

if(WIN32)
    add_definitions(-DUNICODE
            -D_UNICODE
            -DSTRICT
            -DNOMINMAX)
    add_definitions(-D_CRT_SECURE_NO_WARNINGS -D_SCL_SECURE_NO_WARNINGS)
elseif(UNIX)
    # add_compile_options(-pthread) list(APPEND EXTRA_TARGET_LINK_LIBRARIES
    # -pthread)
endif()

set(RE2_SOURCES
        bitstate.cpp
        compile.cpp
        dfa.cpp
        filtered_re2.cpp
        mimics_pcre.cpp
        nfa.cpp
        onepass.cpp
        parse.cpp
        perl_groups.cpp
        prefilter.cpp
        prefilter_tree.cpp
        prog.cpp
        re2.cpp
        regexp.cpp
        set.cpp
        simplify.cpp
        stringpiece.cpp
        tostring.cpp
        unicode_casefold.cpp
        unicode_groups.cpp
        rune.cpp
        strutil.cpp)

add_library(re2 STATIC ${RE2_SOURCES})
target_include_directories(
        re2
        PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>)
