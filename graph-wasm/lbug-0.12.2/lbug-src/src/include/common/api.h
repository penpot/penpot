#pragma once

// Helpers
#if defined _WIN32 || defined __CYGWIN__
#define LBUG_HELPER_DLL_IMPORT __declspec(dllimport)
#define LBUG_HELPER_DLL_EXPORT __declspec(dllexport)
#define LBUG_HELPER_DLL_LOCAL
#define LBUG_HELPER_DEPRECATED __declspec(deprecated)
#else
#define LBUG_HELPER_DLL_IMPORT __attribute__((visibility("default")))
#define LBUG_HELPER_DLL_EXPORT __attribute__((visibility("default")))
#define LBUG_HELPER_DLL_LOCAL __attribute__((visibility("hidden")))
#define LBUG_HELPER_DEPRECATED __attribute__((__deprecated__))
#endif

#ifdef LBUG_STATIC_DEFINE
#define LBUG_API
#else
#ifndef LBUG_API
#ifdef LBUG_EXPORTS
/* We are building this library */
#define LBUG_API LBUG_HELPER_DLL_EXPORT
#else
/* We are using this library */
#define LBUG_API LBUG_HELPER_DLL_IMPORT
#endif
#endif
#endif

#ifndef LBUG_DEPRECATED
#define LBUG_DEPRECATED LBUG_HELPER_DEPRECATED
#endif

#ifndef LBUG_DEPRECATED_EXPORT
#define LBUG_DEPRECATED_EXPORT LBUG_API LBUG_DEPRECATED
#endif
