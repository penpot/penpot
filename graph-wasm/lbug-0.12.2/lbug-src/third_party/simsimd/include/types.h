/**
 *  @file       types.h
 *  @brief      Shared definitions for the SimSIMD library.
 *  @author     Ash Vardanian
 *  @date       October 2, 2023
 *
 *  Defines:
 *  - Sized aliases for numeric types, like: `simsimd_i32_t` and `simsimd_f64_t`.
 *  - Macros for internal compiler/hardware checks, like: `_SIMSIMD_TARGET_ARM`.
 *  - Macros for feature controls, like: `SIMSIMD_TARGET_NEON`
 */
#ifndef SIMSIMD_TYPES_H
#define SIMSIMD_TYPES_H

// Inferring target OS: Windows, MacOS, or Linux
#if defined(WIN32) || defined(_WIN32) || defined(__WIN32__) || defined(__NT__)
#define _SIMSIMD_DEFINED_WINDOWS 1
#elif defined(__APPLE__) && defined(__MACH__)
#define _SIMSIMD_DEFINED_APPLE 1
#elif defined(__linux__)
#define _SIMSIMD_DEFINED_LINUX 1
#endif

// Annotation for the public API symbols:
//
// - `SIMSIMD_PUBLIC` is used for functions that are part of the public API.
// - `SIMSIMD_INTERNAL` is used for internal helper functions with unstable APIs.
// - `SIMSIMD_DYNAMIC` is used for functions that are part of the public API, but are dispatched at
// runtime.
//
#if defined(_WIN32) || defined(__CYGWIN__)
#define SIMSIMD_DYNAMIC __declspec(dllexport)
#define SIMSIMD_PUBLIC inline static
#define SIMSIMD_INTERNAL inline static
#elif defined(__GNUC__) || defined(__clang__)
#define SIMSIMD_DYNAMIC __attribute__((visibility("default")))
#define SIMSIMD_PUBLIC __attribute__((unused)) inline static
#define SIMSIMD_INTERNAL inline static
#else
#define SIMSIMD_DYNAMIC
#define SIMSIMD_PUBLIC inline static
#define SIMSIMD_INTERNAL inline static
#endif

// Compiling for Arm: _SIMSIMD_TARGET_ARM
#if !defined(_SIMSIMD_TARGET_ARM)
#if defined(__aarch64__) || defined(_M_ARM64)
#define _SIMSIMD_TARGET_ARM 1
#else
#define _SIMSIMD_TARGET_ARM 0
#endif // defined(__aarch64__) || defined(_M_ARM64)
#endif // !defined(_SIMSIMD_TARGET_ARM)

// Compiling for x86: _SIMSIMD_TARGET_X86
#if !defined(_SIMSIMD_TARGET_X86)
#if defined(__x86_64__) || defined(_M_X64)
#define _SIMSIMD_TARGET_X86 1
#else
#define _SIMSIMD_TARGET_X86 0
#endif // defined(__x86_64__) || defined(_M_X64)
#endif // !defined(_SIMSIMD_TARGET_X86)

// Compiling for Arm: SIMSIMD_TARGET_NEON
#if !defined(SIMSIMD_TARGET_NEON) || (SIMSIMD_TARGET_NEON && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_NEON)
#define SIMSIMD_TARGET_NEON _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_NEON
#define SIMSIMD_TARGET_NEON 0
#endif // defined(__ARM_NEON)
#endif // !defined(SIMSIMD_TARGET_NEON) || ...

// Compiling for Arm: SIMSIMD_TARGET_NEON_I8
#if !defined(SIMSIMD_TARGET_NEON_I8) || (SIMSIMD_TARGET_NEON_I8 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_NEON)
#define SIMSIMD_TARGET_NEON_I8 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_NEON_I8
#define SIMSIMD_TARGET_NEON_I8 0
#endif // defined(__ARM_NEON)
#endif // !defined(SIMSIMD_TARGET_NEON_I8) || ...

// Compiling for Arm: SIMSIMD_TARGET_NEON_F16
#if !defined(SIMSIMD_TARGET_NEON_F16) || (SIMSIMD_TARGET_NEON_F16 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_NEON)
#define SIMSIMD_TARGET_NEON_F16 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_NEON_F16
#define SIMSIMD_TARGET_NEON_F16 0
#endif // defined(__ARM_NEON)
#endif // !defined(SIMSIMD_TARGET_NEON_F16) || ...

// Compiling for Arm: SIMSIMD_TARGET_NEON_BF16
#if !defined(SIMSIMD_TARGET_NEON_BF16) || (SIMSIMD_TARGET_NEON_BF16 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_NEON)
#define SIMSIMD_TARGET_NEON_BF16 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_NEON_BF16
#define SIMSIMD_TARGET_NEON_BF16 0
#endif // defined(__ARM_NEON)
#endif // !defined(SIMSIMD_TARGET_NEON_BF16) || ...

// Compiling for Arm: SIMSIMD_TARGET_SVE
#if !defined(SIMSIMD_TARGET_SVE) || (SIMSIMD_TARGET_SVE && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_FEATURE_SVE)
#define SIMSIMD_TARGET_SVE _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_SVE
#define SIMSIMD_TARGET_SVE 0
#endif // defined(__ARM_FEATURE_SVE)
#endif // !defined(SIMSIMD_TARGET_SVE) || ...

// Compiling for Arm: SIMSIMD_TARGET_SVE_I8
#if !defined(SIMSIMD_TARGET_SVE_I8) || (SIMSIMD_TARGET_SVE_I8 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_FEATURE_SVE)
#define SIMSIMD_TARGET_SVE_I8 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_SVE_I8
#define SIMSIMD_TARGET_SVE_I8 0
#endif // defined(__ARM_FEATURE_SVE)
#endif // !defined(SIMSIMD_TARGET_SVE_I8) || ...

// Compiling for Arm: SIMSIMD_TARGET_SVE_F16
#if !defined(SIMSIMD_TARGET_SVE_F16) || (SIMSIMD_TARGET_SVE_F16 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_FEATURE_SVE)
#define SIMSIMD_TARGET_SVE_F16 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_SVE_F16
#define SIMSIMD_TARGET_SVE_F16 0
#endif // defined(__ARM_FEATURE_SVE)
#endif // !defined(SIMSIMD_TARGET_SVE_F16) || ...

// Compiling for Arm: SIMSIMD_TARGET_SVE_BF16
#if !defined(SIMSIMD_TARGET_SVE_BF16) || (SIMSIMD_TARGET_SVE_BF16 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_FEATURE_SVE)
#define SIMSIMD_TARGET_SVE_BF16 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_SVE_BF16
#define SIMSIMD_TARGET_SVE_BF16 0
#endif // defined(__ARM_FEATURE_SVE)
#endif // !defined(SIMSIMD_TARGET_SVE_BF16) || ...

// Compiling for Arm: SIMSIMD_TARGET_SVE2
#if !defined(SIMSIMD_TARGET_SVE2) || (SIMSIMD_TARGET_SVE2 && !_SIMSIMD_TARGET_ARM)
#if defined(__ARM_FEATURE_SVE)
#define SIMSIMD_TARGET_SVE2 _SIMSIMD_TARGET_ARM
#else
#undef SIMSIMD_TARGET_SVE2
#define SIMSIMD_TARGET_SVE2 0
#endif // defined(__ARM_FEATURE_SVE)
#endif // !defined(SIMSIMD_TARGET_SVE2) || ...

// Compiling for x86: SIMSIMD_TARGET_HASWELL
//
// Starting with Ivy Bridge, Intel supports the `F16C` extensions for fast half-precision
// to single-precision floating-point conversions. On AMD those instructions
// are supported on all CPUs starting with Jaguar 2009.
// Starting with Sandy Bridge, Intel adds basic AVX support in their CPUs and in 2013
// extends it with AVX2 in the Haswell generation. Moreover, Haswell adds FMA support.
#if !defined(SIMSIMD_TARGET_HASWELL) || (SIMSIMD_TARGET_HASWELL && !_SIMSIMD_TARGET_X86)
#if defined(__AVX2__) && defined(__FMA__) && defined(__F16C__)
#define SIMSIMD_TARGET_HASWELL 1
#else
#undef SIMSIMD_TARGET_HASWELL
#define SIMSIMD_TARGET_HASWELL 0
#endif // defined(__AVX2__)
#endif // !defined(SIMSIMD_TARGET_HASWELL) || ...

// Compiling for x86: SIMSIMD_TARGET_SKYLAKE, SIMSIMD_TARGET_ICE, SIMSIMD_TARGET_GENOA,
// SIMSIMD_TARGET_SAPPHIRE, SIMSIMD_TARGET_TURIN, SIMSIMD_TARGET_SIERRA
//
// To list all available macros for x86, take a recent compiler, like GCC 12 and run:
//      gcc-12 -march=sapphirerapids -dM -E - < /dev/null | egrep "SSE|AVX" | sort
// On Arm machines you may want to check for other flags:
//      gcc-12 -march=native -dM -E - < /dev/null | egrep "NEON|SVE|FP16|FMA" | sort
#if !defined(SIMSIMD_TARGET_SKYLAKE) || (SIMSIMD_TARGET_SKYLAKE && !_SIMSIMD_TARGET_X86)
#if defined(__AVX512F__) && defined(__AVX512CD__) && defined(__AVX512VL__) &&                      \
    defined(__AVX512DQ__) && defined(__AVX512BW__)
#define SIMSIMD_TARGET_SKYLAKE 1
#else
#undef SIMSIMD_TARGET_SKYLAKE
#define SIMSIMD_TARGET_SKYLAKE 0
#endif
#endif // !defined(SIMSIMD_TARGET_SKYLAKE) || ...
#if !defined(SIMSIMD_TARGET_ICE) || (SIMSIMD_TARGET_ICE && !_SIMSIMD_TARGET_X86)
#if defined(__AVX512VNNI__) && defined(__AVX512IFMA__) && defined(__AVX512BITALG__) &&             \
    defined(__AVX512VBMI2__) && defined(__AVX512VPOPCNTDQ__)
#define SIMSIMD_TARGET_ICE 1
#else
#undef SIMSIMD_TARGET_ICE
#define SIMSIMD_TARGET_ICE 0
#endif
#endif // !defined(SIMSIMD_TARGET_ICE) || ...
#if !defined(SIMSIMD_TARGET_GENOA) || (SIMSIMD_TARGET_GENOA && !_SIMSIMD_TARGET_X86)
#if defined(__AVX512BF16__)
#define SIMSIMD_TARGET_GENOA 1
#else
#undef SIMSIMD_TARGET_GENOA
#define SIMSIMD_TARGET_GENOA 0
#endif
#endif // !defined(SIMSIMD_TARGET_GENOA) || ...
#if !defined(SIMSIMD_TARGET_SAPPHIRE) || (SIMSIMD_TARGET_SAPPHIRE && !_SIMSIMD_TARGET_X86)
#if defined(__AVX512FP16__)
#define SIMSIMD_TARGET_SAPPHIRE 1
#else
#undef SIMSIMD_TARGET_SAPPHIRE
#define SIMSIMD_TARGET_SAPPHIRE 0
#endif
#endif // !defined(SIMSIMD_TARGET_SAPPHIRE) || ...
#if !defined(SIMSIMD_TARGET_TURIN) || (SIMSIMD_TARGET_TURIN && !_SIMSIMD_TARGET_X86)
#if defined(__AVX512VP2INTERSECT__)
#define SIMSIMD_TARGET_TURIN 1
#else
#undef SIMSIMD_TARGET_TURIN
#define SIMSIMD_TARGET_TURIN 0
#endif
#endif // !defined(SIMSIMD_TARGET_TURIN) || ...
#if !defined(SIMSIMD_TARGET_SIERRA) || (SIMSIMD_TARGET_SIERRA && !_SIMSIMD_TARGET_X86)
#if defined(__AVX2_VNNI__)
#define SIMSIMD_TARGET_SIERRA 1
#else
#undef SIMSIMD_TARGET_SIERRA
#define SIMSIMD_TARGET_SIERRA 0
#endif
#endif // !defined(SIMSIMD_TARGET_SIERRA) || ...

#ifdef _MSC_VER
#include <intrin.h>
#else

#if SIMSIMD_TARGET_NEON
#include <arm_neon.h>
#endif

#if SIMSIMD_TARGET_SVE || SIMSIMD_TARGET_SVE2
#include <arm_sve.h>
#endif

#if SIMSIMD_TARGET_HASWELL || SIMSIMD_TARGET_SKYLAKE || SIMSIMD_TARGET_ICE ||                      \
    SIMSIMD_TARGET_GENOA || SIMSIMD_TARGET_SAPPHIRE || SIMSIMD_TARGET_TURIN
#include <immintrin.h>
#endif

#endif

#if !defined(SIMSIMD_SQRT)
#include <math.h>
#define SIMSIMD_SQRT(x) (sqrt(x))
#endif

#if !defined(SIMSIMD_RSQRT)
#include <math.h>
#define SIMSIMD_RSQRT(x) (1 / SIMSIMD_SQRT(x))
#endif

#if !defined(SIMSIMD_LOG)
#include <math.h>
#define SIMSIMD_LOG(x) (log(x))
#endif

#if !defined(SIMSIMD_F32_DIVISION_EPSILON)
#define SIMSIMD_F32_DIVISION_EPSILON (1e-7)
#endif

#if !defined(SIMSIMD_F16_DIVISION_EPSILON)
#define SIMSIMD_F16_DIVISION_EPSILON (1e-3)
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef unsigned char simsimd_b8_t;
typedef unsigned char simsimd_i4x2_t;

typedef signed char simsimd_i8_t;
typedef unsigned char simsimd_u8_t;
typedef signed short simsimd_i16_t;
typedef unsigned short simsimd_u16_t;
typedef signed int simsimd_i32_t;
typedef unsigned int simsimd_u32_t;
typedef signed long long simsimd_i64_t;
typedef unsigned long long simsimd_u64_t;

typedef float simsimd_f32_t;
typedef double simsimd_f64_t;

typedef simsimd_u64_t simsimd_size_t;
typedef simsimd_f64_t simsimd_distance_t;

/*  @brief  Half-precision floating-point type.
 *
 *  - GCC or Clang on 64-bit Arm: `__fp16`, may require `-mfp16-format` option.
 *  - GCC or Clang on 64-bit x86: `_Float16`.
 *  - Default: `unsigned short`.
 */
#if !defined(SIMSIMD_NATIVE_F16) || SIMSIMD_NATIVE_F16
#if (defined(__GNUC__) || defined(__clang__)) && (defined(__ARM_ARCH) || defined(__aarch64__)) &&  \
    (defined(__ARM_FP16_FORMAT_IEEE))
#undef SIMSIMD_NATIVE_F16
#define SIMSIMD_NATIVE_F16 1
typedef __fp16 simsimd_f16_t;
#elif ((defined(__GNUC__) || defined(__clang__)) && (defined(__x86_64__) || defined(__i386__)) &&  \
       (defined(__AVX512FP16__)))
typedef _Float16 simsimd_f16_t;
#undef SIMSIMD_NATIVE_F16
#define SIMSIMD_NATIVE_F16 1
#else                                       // Unknown compiler or architecture
#if defined(__GNUC__) || defined(__clang__) // Some compilers don't support warning pragmas
// #warning "Unknown compiler or architecture for float16."
#endif
#undef SIMSIMD_NATIVE_F16
#define SIMSIMD_NATIVE_F16 0
#endif // Unknown compiler or architecture
#endif // !SIMSIMD_NATIVE_F16

#if !SIMSIMD_NATIVE_F16
typedef unsigned short simsimd_f16_t;
#endif

#if !defined(SIMSIMD_NATIVE_BF16) || SIMSIMD_NATIVE_BF16
/**
 *  @brief  Half-precision brain-float type.
 *
 *  - GCC or Clang on 64-bit Arm: `__bf16`
 *  - GCC or Clang on 64-bit x86: `_BFloat16`.
 *  - Default: `unsigned short`.
 *
 *  The compilers have added `__bf16` support in compliance with the x86-64 psABI spec.
 *  The motivation for this new special type is summed up as:
 *
 *      Currently `__bfloat16` is a typedef of short, which creates a problem where the
 *      compiler does not raise any alarms if it is used to add, subtract, multiply or
 *      divide, but the result of the calculation is actually meaningless.
 *      To solve this problem, a real scalar type `__Bfloat16` needs to be introduced.
 *      It is mainly used for intrinsics, not available for C standard operators.
 *      `__Bfloat16` will also be used for movement like passing parameter, load and store,
 *      vector initialization, vector shuffle, and etc. It creates a need for a
 *      corresponding psABI.
 *
 *  @warning Apple Clang has hard time with bf16.
 *  https://developer.apple.com/documentation/xcode/writing-arm64-code-for-apple-platforms
 *  https://forums.developer.apple.com/forums/thread/726201
 *  https://www.phoronix.com/news/GCC-LLVM-bf16-BFloat16-Type
 */
#if (defined(__GNUC__) || defined(__clang__)) && (defined(__ARM_ARCH) || defined(__aarch64__)) &&  \
    (defined(__ARM_BF16_FORMAT_ALTERNATIVE))
#undef SIMSIMD_NATIVE_BF16
#define SIMSIMD_NATIVE_BF16 1
typedef __bf16 simsimd_bf16_t;
#elif ((defined(__GNUC__) || defined(__clang__)) && (defined(__x86_64__) || defined(__i386__)) &&  \
       (defined(__AVX512BF16__)))
typedef __bfloat16 simsimd_bf16_t;
#undef SIMSIMD_NATIVE_BF16
#define SIMSIMD_NATIVE_BF16 1
#else                                       // Unknown compiler or architecture
#if defined(__GNUC__) || defined(__clang__) // Some compilers don't support warning pragmas
// #warning "Unknown compiler or architecture for bfloat16."
#endif
#undef SIMSIMD_NATIVE_BF16
#define SIMSIMD_NATIVE_BF16 0
#endif // Unknown compiler or architecture
#endif // !SIMSIMD_NATIVE_BF16

#if !SIMSIMD_NATIVE_BF16
typedef unsigned short simsimd_bf16_t;
#endif

/**
 *  @brief  Alias for the half-precision floating-point type on Arm.
 *
 *  Clang and GCC bring the `float16_t` symbol when you compile for Aarch64.
 *  MSVC lacks it, and it's `vld1_f16`-like intrinsics are in reality macros,
 *  that cast to 16-bit integers internally, instead of using floats.
 *  Some of those are defined as aliases, so we use `#define` preprocessor
 *  directives instead of `typedef` to avoid errors.
 */
#if _SIMSIMD_TARGET_ARM
#if defined(_MSC_VER)
#define simsimd_f16_for_arm_simd_t simsimd_f16_t
#define simsimd_bf16_for_arm_simd_t simsimd_bf16_t
#else
#define simsimd_f16_for_arm_simd_t float16_t
#define simsimd_bf16_for_arm_simd_t bfloat16_t
#endif
#endif

/*
 *  Let's make sure the sizes of the types are as expected.
 *  In C the `_Static_assert` is only available with C 11 and later.
 */
#define SIMSIMD_STATIC_ASSERT(cond, msg) typedef char static_assertion_##msg[(cond) ? 1 : -1]
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_b8_t) == 1, simsimd_b8_t_must_be_1_byte);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_i4x2_t) == 1, simsimd_i4x2_t_must_be_1_byte);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_i8_t) == 1, simsimd_i8_t_must_be_1_byte);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_u8_t) == 1, simsimd_u8_t_must_be_1_byte);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_i16_t) == 2, simsimd_i16_t_must_be_2_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_u16_t) == 2, simsimd_u16_t_must_be_2_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_i32_t) == 4, simsimd_i32_t_must_be_4_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_u32_t) == 4, simsimd_u32_t_must_be_4_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_i64_t) == 8, simsimd_i64_t_must_be_8_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_u64_t) == 8, simsimd_u64_t_must_be_8_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_f32_t) == 4, simsimd_f32_t_must_be_4_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_f64_t) == 8, simsimd_f64_t_must_be_8_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_f16_t) == 2, simsimd_f16_t_must_be_2_bytes);
SIMSIMD_STATIC_ASSERT(sizeof(simsimd_bf16_t) == 2, simsimd_bf16_t_must_be_2_bytes);

#define SIMSIMD_DEREFERENCE(x) (*(x))
#define SIMSIMD_EXPORT(x, y) *(y) = x

/**
 *  @brief  Returns the value of the half-precision floating-point number,
 *          potentially decompressed into single-precision.
 */
#if !defined(SIMSIMD_F16_TO_F32)
#if SIMSIMD_NATIVE_F16
#define SIMSIMD_F16_TO_F32(x) (SIMSIMD_DEREFERENCE(x))
#define SIMSIMD_F32_TO_F16(x, y) (SIMSIMD_EXPORT(x, y))
#else
#define SIMSIMD_F16_TO_F32(x) (simsimd_f16_to_f32(x))
#define SIMSIMD_F32_TO_F16(x, y) (simsimd_f32_to_f16(x, y))
#endif
#endif

/**
 *  @brief  Returns the value of the half-precision brain floating-point number,
 *          potentially decompressed into single-precision.
 */
#if !defined(SIMSIMD_BF16_TO_F32)
#if SIMSIMD_NATIVE_BF16
#define SIMSIMD_BF16_TO_F32(x) (SIMSIMD_DEREFERENCE(x))
#define SIMSIMD_F32_TO_BF16(x, y) (SIMSIMD_EXPORT(x, y))
#else
#define SIMSIMD_BF16_TO_F32(x) (simsimd_bf16_to_f32(x))
#define SIMSIMD_F32_TO_BF16(x, y) (simsimd_f32_to_bf16(x, y))
#endif
#endif

#if !defined(SIMSIMD_F32_TO_I8)
#define SIMSIMD_F32_TO_I8(x, y)                                                                    \
    *(y) = (simsimd_i8_t)((x) > 127 ? 127 :                                                        \
                                      ((x) < -128 ? -128 : (int)((x) + ((x) < 0 ? -0.5f : 0.5f))))
#endif
#if !defined(SIMSIMD_F32_TO_U8)
#define SIMSIMD_F32_TO_U8(x, y)                                                                    \
    *(y) = (simsimd_u8_t)((x) > 255 ? 255 : ((x) < 0 ? 0 : (int)((x) + ((x) < 0 ? -0.5f : 0.5f))))
#endif
#if !defined(SIMSIMD_F64_TO_I8)
#define SIMSIMD_F64_TO_I8(x, y)                                                                    \
    *(y) = (simsimd_i8_t)((x) > 127 ? 127 :                                                        \
                                      ((x) < -128 ? -128 : (int)((x) + ((x) < 0 ? -0.5 : 0.5))))
#endif
#if !defined(SIMSIMD_F64_TO_U8)
#define SIMSIMD_F64_TO_U8(x, y)                                                                    \
    *(y) = (simsimd_u8_t)((x) > 255 ? 255 : ((x) < 0 ? 0 : (int)((x) + ((x) < 0 ? -0.5 : 0.5))))
#endif

/** @brief  Convenience type for half-precision floating-point type conversions. */
typedef union {
    unsigned i;
    float f;
} simsimd_f32i32_t;

/** @brief  Convenience type addressing the real and imaginary parts of a half-precision complex
 * number. */
typedef struct {
    simsimd_f16_t real;
    simsimd_f16_t imag;
} simsimd_f16c_t;

/** @brief  Convenience type addressing the real and imaginary parts of a half-precision brain-float
 * complex number. */
typedef struct {
    simsimd_bf16_t real;
    simsimd_bf16_t imag;
} simsimd_bf16c_t;

/** @brief  Convenience type addressing the real and imaginary parts of a single-precision complex
 * number. */
typedef struct {
    simsimd_f32_t real;
    simsimd_f32_t imag;
} simsimd_f32c_t;

/** @brief  Convenience type addressing the real and imaginary parts of a double-precision complex
 * number. */
typedef struct {
    simsimd_f64_t real;
    simsimd_f64_t imag;
} simsimd_f64c_t;

/**
 *  @brief  Computes `1/sqrt(x)` using the trick from Quake 3,
 *          replacing the magic numbers with the ones suggested by Jan Kadlec.
 *
 *  Subsequent additions by hardware manufacturers have made this algorithm redundant for the most
 * part. For example, on x86, Intel introduced the SSE instruction `rsqrtss` in 1999. In a 2009
 * benchmark on the Intel Core 2, this instruction took 0.85ns per float compared to 3.54ns for the
 * fast inverse square root algorithm, and had less error. Carmack's Magic Number `rsqrt` had an
 * average error of 0.0990%, while SSE `rsqrtss` had 0.0094%, a 10x improvement.
 *
 *  https://web.archive.org/web/20210208132927/http://assemblyrequired.crashworks.org/timing-square-root/
 *  https://stackoverflow.com/a/41460625/2766161
 */
SIMSIMD_INTERNAL simsimd_f32_t simsimd_approximate_inverse_square_root(simsimd_f32_t number) {
    simsimd_f32i32_t conv;
    conv.f = number;
    conv.i = 0x5F1FFFF9 - (conv.i >> 1);
    // Refine using a Newton-Raphson step for better accuracy
    conv.f *= 0.703952253f * (2.38924456f - number * conv.f * conv.f);
    return conv.f;
}

/**
 *  @brief  Approximates `sqrt(x)` using the fast inverse square root trick
 *          with adjustments for direct square root approximation.
 *
 *  Similar to `rsqrt` approximation but multiplies by `number` to get `sqrt`.
 *  This technique is useful where `sqrt` approximation is needed in performance-critical code,
 *  though modern hardware provides optimized alternatives.
 */
SIMSIMD_INTERNAL simsimd_f32_t simsimd_approximate_square_root(simsimd_f32_t number) {
    return number * simsimd_approximate_inverse_square_root(number);
}

/**
 *  @brief  Computes `log(x)` using the Mercator series.
 *          The series converges to the natural logarithm for args between -1 and 1.
 *          Published in 1668 in "Logarithmotechnia".
 */
SIMSIMD_INTERNAL simsimd_f32_t simsimd_approximate_log(simsimd_f32_t number) {
    simsimd_f32_t x = number - 1;
    simsimd_f32_t x2 = x * x;
    simsimd_f32_t x3 = x * x * x;
    return x - x2 / 2 + x3 / 3;
}

/**
 *  @brief  For compilers that don't natively support the `_Float16` type,
 *          upcasts contents into a more conventional `float`.
 *
 *  @warning  This function won't handle boundary conditions well.
 *
 *  https://stackoverflow.com/a/60047308
 *  https://gist.github.com/milhidaka/95863906fe828198f47991c813dbe233
 *  https://github.com/OpenCyphal/libcanard/blob/636795f4bc395f56af8d2c61d3757b5e762bb9e5/canard.c#L811-L834
 */
SIMSIMD_INTERNAL simsimd_f32_t simsimd_f16_to_f32(simsimd_f16_t const* x_ptr) {
    unsigned short x = *(unsigned short const*)x_ptr;
    unsigned int exponent = (x & 0x7C00) >> 10;
    unsigned int mantissa = (x & 0x03FF) << 13;
    simsimd_f32i32_t mantissa_conv;
    mantissa_conv.f = (float)mantissa;
    unsigned int v = (mantissa_conv.i) >> 23;
    simsimd_f32i32_t conv;
    conv.i = (x & 0x8000) << 16 | (exponent != 0) * ((exponent + 112) << 23 | mantissa) |
             ((exponent == 0) & (mantissa != 0)) *
                 ((v - 37) << 23 | ((mantissa << (150 - v)) & 0x007FE000));
    return conv.f;
}

/**
 *  @brief  Compresses a `float` to an `f16` representation (IEEE-754 16-bit floating-point format).
 *
 *  @warning  This function won't handle boundary conditions well.
 *
 *  https://stackoverflow.com/a/60047308
 *  https://gist.github.com/milhidaka/95863906fe828198f47991c813dbe233
 *  https://github.com/OpenCyphal/libcanard/blob/636795f4bc395f56af8d2c61d3757b5e762bb9e5/canard.c#L811-L834
 */
SIMSIMD_INTERNAL void simsimd_f32_to_f16(simsimd_f32_t x, simsimd_f16_t* result_ptr) {
    simsimd_f32i32_t conv;
    conv.f = x;
    unsigned int b = conv.i + 0x00001000;
    unsigned int e = (b & 0x7F800000) >> 23;
    unsigned int m = b & 0x007FFFFF;
    unsigned short result = ((b & 0x80000000) >> 16) |
                            (e > 112) * ((((e - 112) << 10) & 0x7C00) | (m >> 13)) |
                            ((e < 113) & (e > 101)) * ((((0x007FF000 + m) >> (125 - e)) + 1) >> 1) |
                            ((e > 143) * 0x7FFF);
    *(unsigned short*)result_ptr = result;
}

/**
 *  @brief  For compilers that don't natively support the `__bf16` type,
 *          upcasts contents into a more conventional `float`.
 *
 *  https://stackoverflow.com/questions/55253233/convert-fp32-to-bfloat16-in-c/55254307#55254307
 *  https://cloud.google.com/blog/products/ai-machine-learning/bfloat16-the-secret-to-high-performance-on-cloud-tpus
 */
SIMSIMD_INTERNAL simsimd_f32_t simsimd_bf16_to_f32(simsimd_bf16_t const* x_ptr) {
    unsigned short x = *(unsigned short const*)x_ptr;
    simsimd_f32i32_t conv;
    conv.i = x << 16; // Zero extends the mantissa
    return conv.f;
}

/**
 *  @brief  Compresses a `float` to a `bf16` representation.
 *
 *  https://stackoverflow.com/questions/55253233/convert-fp32-to-bfloat16-in-c/55254307#55254307
 *  https://cloud.google.com/blog/products/ai-machine-learning/bfloat16-the-secret-to-high-performance-on-cloud-tpus
 */
SIMSIMD_INTERNAL void simsimd_f32_to_bf16(simsimd_f32_t x, simsimd_bf16_t* result_ptr) {
    simsimd_f32i32_t conv;
    conv.f = x;
    conv.i += 0x8000; // Rounding is optional
    conv.i >>= 16;
    // The top 16 bits will be zeroed out anyways
    // conv.i &= 0xFFFF;
    *(unsigned short*)result_ptr = (unsigned short)conv.i;
}

SIMSIMD_INTERNAL simsimd_u32_t simsimd_u32_rol(simsimd_u32_t x, int n) {
    return (x << n) | (x >> (32 - n));
}
SIMSIMD_INTERNAL simsimd_u16_t simsimd_u16_rol(simsimd_u16_t x, int n) {
    return (x << n) | (x >> (16 - n));
}
SIMSIMD_INTERNAL simsimd_u8_t simsimd_u8_rol(simsimd_u8_t x, int n) {
    return (x << n) | (x >> (8 - n));
}
SIMSIMD_INTERNAL simsimd_u32_t simsimd_u32_ror(simsimd_u32_t x, int n) {
    return (x >> n) | (x << (32 - n));
}
SIMSIMD_INTERNAL simsimd_u16_t simsimd_u16_ror(simsimd_u16_t x, int n) {
    return (x >> n) | (x << (16 - n));
}
SIMSIMD_INTERNAL simsimd_u8_t simsimd_u8_ror(simsimd_u8_t x, int n) {
    return (x >> n) | (x << (8 - n));
}

#ifdef __cplusplus
} // extern "C"
#endif

#endif
