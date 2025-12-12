#pragma once

//! The new version of snappy is much faster when compiled with clang, but slower when compiled with GCC
//! For Lbug, we default to the old version if the compiler is not clang
#ifndef SNAPPY_NEW_VERSION
#ifdef __clang__
#define SNAPPY_NEW_VERSION true
#else
#define SNAPPY_NEW_VERSION false
#endif
#endif