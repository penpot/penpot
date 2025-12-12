/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <algorithm>
#include <any>
#include <atomic>
#include <bitset>
#include <cassert>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <stack>
#include <string>
#include <string_view>
#include <typeinfo>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <chrono>

// Defines for the Guid class and other platform dependent stuff.
#ifdef _WIN32
  #ifdef _MSC_VER
    #pragma warning (disable: 4250) // Class inherits by dominance.
    #pragma warning (disable: 4512) // assignment operator could not be generated

    #if _MSC_VER < 1900
      // Before VS 2015 code like "while (true)" will create a (useless) warning in level 4.
      #pragma warning (disable: 4127) // conditional expression is constant
    #endif
  #endif

  #ifdef _WIN64
    typedef __int64 ssize_t;
  #else
    typedef __int32 ssize_t;
  #endif

  #ifdef ANTLR4CPP_EXPORTS
    #define ANTLR4CPP_PUBLIC __declspec(dllexport)
  #else
    #ifdef ANTLR4CPP_STATIC
      #define ANTLR4CPP_PUBLIC
    #else
      #define ANTLR4CPP_PUBLIC __declspec(dllimport)
    #endif
  #endif

#elif defined(__APPLE__)
  #if __GNUC__ >= 4
    #define ANTLR4CPP_PUBLIC __attribute__ ((visibility ("default")))
  #else
    #define ANTLR4CPP_PUBLIC
  #endif
#else
  #if __GNUC__ >= 6
    #define ANTLR4CPP_PUBLIC __attribute__ ((visibility ("default")))
  #else
    #define ANTLR4CPP_PUBLIC
  #endif
#endif

#ifdef __has_builtin
#define ANTLR4CPP_HAVE_BUILTIN(x) __has_builtin(x)
#else
#define ANTLR4CPP_HAVE_BUILTIN(x) 0
#endif

#define ANTLR4CPP_INTERNAL_STRINGIFY(x) #x
#define ANTLR4CPP_STRINGIFY(x) ANTLR4CPP_INTERNAL_STRINGIFY(x)

// We use everything from the C++ standard library by default.
#ifndef ANTLR4CPP_USING_ABSEIL
#define ANTLR4CPP_USING_ABSEIL 0
#endif

#include "support/Declarations.h"

// We have to undefine this symbol as ANTLR will use this name for own members and even
// generated functions. Because EOF is a global macro we cannot use e.g. a namespace scope to disambiguate.
#ifdef EOF
#undef EOF
#endif

#define INVALID_INDEX std::numeric_limits<size_t>::max()
template<class T> using Ref = std::shared_ptr<T>;
