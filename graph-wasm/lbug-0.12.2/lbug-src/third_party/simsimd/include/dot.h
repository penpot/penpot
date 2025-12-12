/**
 *  @file       dot.h
 *  @brief      SIMD-accelerated Dot Products for Real and Complex numbers.
 *  @author     Ash Vardanian
 *  @date       February 24, 2024
 *
 *  Contains:
 *  - Dot Product for Real and Complex vectors
 *  - Conjugate Dot Product for Complex vectors
 *
 *  For datatypes:
 *  - 64-bit IEEE floating point numbers
 *  - 32-bit IEEE floating point numbers
 *  - 16-bit IEEE floating point numbers
 *  - 16-bit brain floating point numbers
 *  - 8-bit unsigned integers
 *  - 8-bit signed integers
 *
 *  For hardware architectures:
 *  - Arm: NEON, SVE
 *  - x86: Haswell, Ice Lake, Skylake, Genoa, Sapphire
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 */
#ifndef SIMSIMD_DOT_H
#define SIMSIMD_DOT_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

// clang-format off

/*  Serial backends for all numeric types.
 *  By default they use 32-bit arithmetic, unless the arguments themselves contain 64-bit floats.
 *  For double-precision computation check out the "*_accurate" variants of those "*_serial" functions.
 */
SIMSIMD_PUBLIC void simsimd_dot_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f64c_serial(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f64c_serial(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f32c_serial(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f32c_serial(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f16c_serial(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f16c_serial(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_bf16c_serial(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_bf16c_serial(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_i8_serial(simsimd_i8_t const* a, simsimd_i8_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_u8_serial(simsimd_u8_t const* a, simsimd_u8_t const* b, simsimd_size_t n, simsimd_distance_t* result);

/*  Double-precision serial backends for all numeric types.
 *  For single-precision computation check out the "*_serial" counterparts of those "*_accurate" functions.
 */
SIMSIMD_PUBLIC void simsimd_dot_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f32c_accurate(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f32c_accurate(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f16c_accurate(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f16c_accurate(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_bf16c_accurate(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_bf16c_accurate(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

/*  SIMD-powered backends for Arm NEON, mostly using 32-bit arithmetic over 128-bit words.
 *  By far the most portable backend, covering most Arm v8 devices, over a billion phones, and almost all
 *  server CPUs produced before 2023.
 */
SIMSIMD_PUBLIC void simsimd_dot_f32_neon(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f32c_neon(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f32c_neon(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f16_neon(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f16c_neon(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f16c_neon(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_i8_neon(simsimd_i8_t const* a, simsimd_i8_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_u8_neon(simsimd_u8_t const* a, simsimd_u8_t const* b, simsimd_size_t n, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_dot_bf16_neon(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_bf16c_neon(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_bf16c_neon(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

/*  SIMD-powered backends for Arm SVE, mostly using 32-bit arithmetic over variable-length platform-defined word sizes.
 *  Designed for Arm Graviton 3, Microsoft Cobalt, as well as Nvidia Grace and newer Ampere Altra CPUs.
 */
SIMSIMD_PUBLIC void simsimd_dot_f32_sve(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f32c_sve(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f32c_sve(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f16_sve(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f16c_sve(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f16c_sve(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f64_sve(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f64c_sve(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f64c_sve(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

/*  SIMD-powered backends for AVX2 CPUs of Haswell generation and newer, using 32-bit arithmetic over 256-bit words.
 *  First demonstrated in 2011, at least one Haswell-based processor was still being sold in 2022 — the Pentium G3420.
 *  Practically all modern x86 CPUs support AVX2, FMA, and F16C, making it a perfect baseline for SIMD algorithms.
 *  On other hand, there is no need to implement AVX2 versions of `f32` and `f64` functions, as those are
 *  properly vectorized by recent compilers.
 */
SIMSIMD_PUBLIC void simsimd_dot_f32_haswell(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f32c_haswell(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f32c_haswell(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f16_haswell(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f16c_haswell(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f16c_haswell(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_bf16_haswell(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_dot_i8_haswell(simsimd_i8_t const* a, simsimd_i8_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_u8_haswell(simsimd_u8_t const* a, simsimd_u8_t const* b, simsimd_size_t n, simsimd_distance_t* result);

/*  SIMD-powered backends for various generations of AVX512 CPUs.
 *  Skylake is handy, as it supports masked loads and other operations, avoiding the need for the tail loop.
 *  Ice Lake added VNNI, VPOPCNTDQ, IFMA, VBMI, VAES, GFNI, VBMI2, BITALG, VPCLMULQDQ, and other extensions for integral operations.
 *  Genoa added only BF16.
 *  Sapphire Rapids added tiled matrix operations, but we are most interested in the new mixed-precision FMA instructions.
 */
SIMSIMD_PUBLIC void simsimd_dot_f64_skylake(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f64c_skylake(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f64c_skylake(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f32_skylake(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f32c_skylake(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f32c_skylake(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_i8_ice(simsimd_i8_t const* a, simsimd_i8_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_u8_ice(simsimd_u8_t const* a, simsimd_u8_t const* b, simsimd_size_t n, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_dot_bf16_genoa(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_bf16c_genoa(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_bf16c_genoa(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_f16_sapphire(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_dot_f16c_sapphire(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_vdot_f16c_sapphire(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_size_t n, simsimd_distance_t* results);

SIMSIMD_PUBLIC void simsimd_dot_i8_sierra(simsimd_i8_t const* a, simsimd_i8_t const* b, simsimd_size_t n, simsimd_distance_t* result);
// clang-format on

#define SIMSIMD_MAKE_DOT(name, input_type, accumulator_type, load_and_convert)                                 \
    SIMSIMD_PUBLIC void simsimd_dot_##input_type##_##name(simsimd_##input_type##_t const *a,                   \
                                                          simsimd_##input_type##_t const *b, simsimd_size_t n, \
                                                          simsimd_distance_t *result) {                        \
        simsimd_##accumulator_type##_t ab = 0;                                                                 \
        for (simsimd_size_t i = 0; i != n; ++i) {                                                              \
            simsimd_##accumulator_type##_t ai = load_and_convert(a + i);                                       \
            simsimd_##accumulator_type##_t bi = load_and_convert(b + i);                                       \
            ab += ai * bi;                                                                                     \
        }                                                                                                      \
        *result = ab;                                                                                          \
    }

#define SIMSIMD_MAKE_COMPLEX_DOT(name, input_type, accumulator_type, load_and_convert)                               \
    SIMSIMD_PUBLIC void simsimd_dot_##input_type##_##name(simsimd_##input_type##_t const *a_pairs,                   \
                                                          simsimd_##input_type##_t const *b_pairs,                   \
                                                          simsimd_size_t count_pairs, simsimd_distance_t *results) { \
        simsimd_##accumulator_type##_t ab_real = 0, ab_imag = 0;                                                     \
        for (simsimd_size_t i = 0; i != count_pairs; ++i) {                                                          \
            simsimd_##accumulator_type##_t ar = load_and_convert(&(a_pairs + i)->real);                              \
            simsimd_##accumulator_type##_t br = load_and_convert(&(b_pairs + i)->real);                              \
            simsimd_##accumulator_type##_t ai = load_and_convert(&(a_pairs + i)->imag);                              \
            simsimd_##accumulator_type##_t bi = load_and_convert(&(b_pairs + i)->imag);                              \
            ab_real += ar * br - ai * bi;                                                                            \
            ab_imag += ar * bi + ai * br;                                                                            \
        }                                                                                                            \
        results[0] = ab_real;                                                                                        \
        results[1] = ab_imag;                                                                                        \
    }

#define SIMSIMD_MAKE_COMPLEX_VDOT(name, input_type, accumulator_type, load_and_convert)                               \
    SIMSIMD_PUBLIC void simsimd_vdot_##input_type##_##name(simsimd_##input_type##_t const *a_pairs,                   \
                                                           simsimd_##input_type##_t const *b_pairs,                   \
                                                           simsimd_size_t count_pairs, simsimd_distance_t *results) { \
        simsimd_##accumulator_type##_t ab_real = 0, ab_imag = 0;                                                      \
        for (simsimd_size_t i = 0; i != count_pairs; ++i) {                                                           \
            simsimd_##accumulator_type##_t ar = load_and_convert(&(a_pairs + i)->real);                               \
            simsimd_##accumulator_type##_t br = load_and_convert(&(b_pairs + i)->real);                               \
            simsimd_##accumulator_type##_t ai = load_and_convert(&(a_pairs + i)->imag);                               \
            simsimd_##accumulator_type##_t bi = load_and_convert(&(b_pairs + i)->imag);                               \
            ab_real += ar * br + ai * bi;                                                                             \
            ab_imag += ar * bi - ai * br;                                                                             \
        }                                                                                                             \
        results[0] = ab_real;                                                                                         \
        results[1] = ab_imag;                                                                                         \
    }

SIMSIMD_MAKE_DOT(serial, f64, f64, SIMSIMD_DEREFERENCE)           // simsimd_dot_f64_serial
SIMSIMD_MAKE_COMPLEX_DOT(serial, f64c, f64, SIMSIMD_DEREFERENCE)  // simsimd_dot_f64c_serial
SIMSIMD_MAKE_COMPLEX_VDOT(serial, f64c, f64, SIMSIMD_DEREFERENCE) // simsimd_vdot_f64c_serial

SIMSIMD_MAKE_DOT(serial, f32, f32, SIMSIMD_DEREFERENCE)           // simsimd_dot_f32_serial
SIMSIMD_MAKE_COMPLEX_DOT(serial, f32c, f32, SIMSIMD_DEREFERENCE)  // simsimd_dot_f32c_serial
SIMSIMD_MAKE_COMPLEX_VDOT(serial, f32c, f32, SIMSIMD_DEREFERENCE) // simsimd_vdot_f32c_serial

SIMSIMD_MAKE_DOT(serial, f16, f32, SIMSIMD_F16_TO_F32)           // simsimd_dot_f16_serial
SIMSIMD_MAKE_COMPLEX_DOT(serial, f16c, f32, SIMSIMD_F16_TO_F32)  // simsimd_dot_f16c_serial
SIMSIMD_MAKE_COMPLEX_VDOT(serial, f16c, f32, SIMSIMD_F16_TO_F32) // simsimd_vdot_f16c_serial

SIMSIMD_MAKE_DOT(serial, bf16, f32, SIMSIMD_BF16_TO_F32)           // simsimd_dot_bf16_serial
SIMSIMD_MAKE_COMPLEX_DOT(serial, bf16c, f32, SIMSIMD_BF16_TO_F32)  // simsimd_dot_bf16c_serial
SIMSIMD_MAKE_COMPLEX_VDOT(serial, bf16c, f32, SIMSIMD_BF16_TO_F32) // simsimd_vdot_bf16c_serial

SIMSIMD_MAKE_DOT(serial, i8, i64, SIMSIMD_DEREFERENCE) // simsimd_dot_i8_serial
SIMSIMD_MAKE_DOT(serial, u8, i64, SIMSIMD_DEREFERENCE) // simsimd_dot_u8_serial

SIMSIMD_MAKE_DOT(accurate, f32, f64, SIMSIMD_DEREFERENCE)           // simsimd_dot_f32_accurate
SIMSIMD_MAKE_COMPLEX_DOT(accurate, f32c, f64, SIMSIMD_DEREFERENCE)  // simsimd_dot_f32c_accurate
SIMSIMD_MAKE_COMPLEX_VDOT(accurate, f32c, f64, SIMSIMD_DEREFERENCE) // simsimd_vdot_f32c_accurate

SIMSIMD_MAKE_DOT(accurate, f16, f64, SIMSIMD_F16_TO_F32)           // simsimd_dot_f16_accurate
SIMSIMD_MAKE_COMPLEX_DOT(accurate, f16c, f64, SIMSIMD_F16_TO_F32)  // simsimd_dot_f16c_accurate
SIMSIMD_MAKE_COMPLEX_VDOT(accurate, f16c, f64, SIMSIMD_F16_TO_F32) // simsimd_vdot_f16c_accurate

SIMSIMD_MAKE_DOT(accurate, bf16, f64, SIMSIMD_BF16_TO_F32)           // simsimd_dot_bf16_accurate
SIMSIMD_MAKE_COMPLEX_DOT(accurate, bf16c, f64, SIMSIMD_BF16_TO_F32)  // simsimd_dot_bf16c_accurate
SIMSIMD_MAKE_COMPLEX_VDOT(accurate, bf16c, f64, SIMSIMD_BF16_TO_F32) // simsimd_vdot_bf16c_accurate

#if _SIMSIMD_TARGET_ARM
#if SIMSIMD_TARGET_NEON
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd"))), apply_to = function)

SIMSIMD_INTERNAL float32x4_t _simsimd_partial_load_f32x4_neon(simsimd_f32_t const *x, simsimd_size_t n) {
    union {
        float32x4_t vec;
        simsimd_f32_t scalars[4];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[i] = x[i];
    for (; i < 4; ++i) result.scalars[i] = 0;
    return result.vec;
}

SIMSIMD_PUBLIC void simsimd_dot_f32_neon(simsimd_f32_t const *a_scalars, simsimd_f32_t const *b_scalars,
                                         simsimd_size_t count_scalars, simsimd_distance_t *result) {
    float32x4_t ab_vec = vdupq_n_f32(0);
    simsimd_size_t idx_scalars = 0;
    for (; idx_scalars + 4 <= count_scalars; idx_scalars += 4) {
        float32x4_t a_vec = vld1q_f32(a_scalars + idx_scalars);
        float32x4_t b_vec = vld1q_f32(b_scalars + idx_scalars);
        ab_vec = vfmaq_f32(ab_vec, a_vec, b_vec);
    }
    simsimd_f32_t ab = vaddvq_f32(ab_vec);
    for (; idx_scalars < count_scalars; ++idx_scalars) ab += a_scalars[idx_scalars] * b_scalars[idx_scalars];
    *result = ab;
}

SIMSIMD_PUBLIC void simsimd_dot_f32c_neon(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                          simsimd_size_t count_pairs, simsimd_distance_t *results) {
    float32x4_t ab_real_vec = vdupq_n_f32(0);
    float32x4_t ab_imag_vec = vdupq_n_f32(0);
    simsimd_size_t idx_pairs = 0;
    for (; idx_pairs + 4 <= count_pairs; idx_pairs += 4) {
        // Unpack the input arrays into real and imaginary parts:
        float32x4x2_t a_vec = vld2q_f32((simsimd_f32_t const *)(a_pairs + idx_pairs));
        float32x4x2_t b_vec = vld2q_f32((simsimd_f32_t const *)(b_pairs + idx_pairs));
        float32x4_t a_real_vec = a_vec.val[0];
        float32x4_t a_imag_vec = a_vec.val[1];
        float32x4_t b_real_vec = b_vec.val[0];
        float32x4_t b_imag_vec = b_vec.val[1];

        // Compute the dot product:
        ab_real_vec = vfmaq_f32(ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = vfmsq_f32(ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_imag_vec, b_real_vec);
    }

    // Reduce horizontal sums:
    simsimd_f32_t ab_real = vaddvq_f32(ab_real_vec);
    simsimd_f32_t ab_imag = vaddvq_f32(ab_imag_vec);

    // Handle the tail:
    for (; idx_pairs != count_pairs; ++idx_pairs) {
        simsimd_f32c_t a_pair = a_pairs[idx_pairs], b_pair = b_pairs[idx_pairs];
        simsimd_f32_t ar = a_pair.real, ai = a_pair.imag, br = b_pair.real, bi = b_pair.imag;
        ab_real += ar * br - ai * bi;
        ab_imag += ar * bi + ai * br;
    }
    results[0] = ab_real;
    results[1] = ab_imag;
}

SIMSIMD_PUBLIC void simsimd_vdot_f32c_neon(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                           simsimd_size_t count_pairs, simsimd_distance_t *results) {
    float32x4_t ab_real_vec = vdupq_n_f32(0);
    float32x4_t ab_imag_vec = vdupq_n_f32(0);
    simsimd_size_t idx_pairs = 0;
    for (; idx_pairs + 4 <= count_pairs; idx_pairs += 4) {
        // Unpack the input arrays into real and imaginary parts:
        float32x4x2_t a_vec = vld2q_f32((simsimd_f32_t const *)(a_pairs + idx_pairs));
        float32x4x2_t b_vec = vld2q_f32((simsimd_f32_t const *)(b_pairs + idx_pairs));
        float32x4_t a_real_vec = a_vec.val[0];
        float32x4_t a_imag_vec = a_vec.val[1];
        float32x4_t b_real_vec = b_vec.val[0];
        float32x4_t b_imag_vec = b_vec.val[1];

        // Compute the dot product:
        ab_real_vec = vfmaq_f32(ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = vfmaq_f32(ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = vfmsq_f32(ab_imag_vec, a_imag_vec, b_real_vec);
    }

    // Reduce horizontal sums:
    simsimd_f32_t ab_real = vaddvq_f32(ab_real_vec);
    simsimd_f32_t ab_imag = vaddvq_f32(ab_imag_vec);

    // Handle the tail:
    for (; idx_pairs != count_pairs; ++idx_pairs) {
        simsimd_f32c_t a_pair = a_pairs[idx_pairs], b_pair = b_pairs[idx_pairs];
        simsimd_f32_t ar = a_pair.real, ai = a_pair.imag, br = b_pair.real, bi = b_pair.imag;
        ab_real += ar * br + ai * bi;
        ab_imag += ar * bi - ai * br;
    }
    results[0] = ab_real;
    results[1] = ab_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON

#if SIMSIMD_TARGET_NEON_I8
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+dotprod")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+dotprod"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_i8_neon(simsimd_i8_t const *a_scalars, simsimd_i8_t const *b_scalars,
                                        simsimd_size_t count_scalars, simsimd_distance_t *result) {

    int32x4_t ab_vec = vdupq_n_s32(0);
    simsimd_size_t idx_scalars = 0;

    // If the 128-bit `vdot_s32` intrinsic is unavailable, we can use the 64-bit `vdot_s32`.
    // for (simsimd_size_t idx_scalars = 0; idx_scalars != n; idx_scalars += 8) {
    //     int16x8_t a_vec = vmovl_s8(vld1_s8(a_scalars + idx_scalars));
    //     int16x8_t b_vec = vmovl_s8(vld1_s8(b_scalars + idx_scalars));
    //     int16x8_t ab_part_vec = vmulq_s16(a_vec, b_vec);
    //     ab_vec = vaddq_s32(ab_vec, vaddq_s32(vmovl_s16(vget_high_s16(ab_part_vec)), //
    //                                          vmovl_s16(vget_low_s16(ab_part_vec))));
    // }
    for (; idx_scalars + 16 <= count_scalars; idx_scalars += 16) {
        int8x16_t a_vec = vld1q_s8(a_scalars + idx_scalars);
        int8x16_t b_vec = vld1q_s8(b_scalars + idx_scalars);
        ab_vec = vdotq_s32(ab_vec, a_vec, b_vec);
    }

    // Take care of the tail:
    simsimd_i32_t ab = vaddvq_s32(ab_vec);
    for (; idx_scalars < count_scalars; ++idx_scalars) {
        simsimd_i32_t ai = a_scalars[idx_scalars], bi = b_scalars[idx_scalars];
        ab += ai * bi;
    }

    *result = ab;
}

SIMSIMD_PUBLIC void simsimd_dot_u8_neon(simsimd_u8_t const *a_scalars, simsimd_u8_t const *b_scalars,
                                        simsimd_size_t count_scalars, simsimd_distance_t *result) {

    uint32x4_t ab_vec = vdupq_n_u32(0);
    simsimd_size_t idx_scalars = 0;
    for (; idx_scalars + 16 <= count_scalars; idx_scalars += 16) {
        uint8x16_t a_vec = vld1q_u8(a_scalars + idx_scalars);
        uint8x16_t b_vec = vld1q_u8(b_scalars + idx_scalars);
        ab_vec = vdotq_u32(ab_vec, a_vec, b_vec);
    }

    // Take care of the tail:
    simsimd_u32_t ab = vaddvq_u32(ab_vec);
    for (; idx_scalars < count_scalars; ++idx_scalars) {
        simsimd_u32_t ai = a_scalars[idx_scalars], bi = b_scalars[idx_scalars];
        ab += ai * bi;
    }

    *result = ab;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON_I8

#if SIMSIMD_TARGET_NEON_F16
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd+fp16")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd+fp16"))), apply_to = function)

SIMSIMD_INTERNAL float16x4_t _simsimd_partial_load_f16x4_neon(simsimd_f16_t const *x, simsimd_size_t n) {
    // In case the software emulation for `f16` scalars is enabled, the `simsimd_f16_to_f32`
    // function will run. It is extremely slow, so even for the tail, let's combine serial
    // loads and stores with vectorized math.
    union {
        float16x4_t vec;
        simsimd_f16_t scalars[4];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[i] = x[i];
    for (; i < 4; ++i) result.scalars[i] = 0;
    return result.vec;
}

SIMSIMD_PUBLIC void simsimd_dot_f16_neon(simsimd_f16_t const *a_scalars, simsimd_f16_t const *b_scalars,
                                         simsimd_size_t count_scalars, simsimd_distance_t *result) {
    float32x4_t a_vec, b_vec;
    float32x4_t ab_vec = vdupq_n_f32(0);

simsimd_dot_f16_neon_cycle:
    if (count_scalars < 4) {
        a_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(a_scalars, count_scalars));
        b_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(b_scalars, count_scalars));
        count_scalars = 0;
    }
    else {
        a_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)a_scalars));
        b_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)b_scalars));
        a_scalars += 4, b_scalars += 4, count_scalars -= 4;
    }
    ab_vec = vfmaq_f32(ab_vec, a_vec, b_vec);
    if (count_scalars) goto simsimd_dot_f16_neon_cycle;
    *result = vaddvq_f32(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f16c_neon(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                          simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // A nicer approach is to use `f16` arithmetic for the dot product, but that requires
    // FMLA extensions available on Arm v8.3 and later. That we can also process 16 entries
    // at once. That's how the original implementation worked, but compiling it was a nightmare :)
    float32x4_t ab_real_vec = vdupq_n_f32(0);
    float32x4_t ab_imag_vec = vdupq_n_f32(0);

    while (count_pairs >= 4) {
        // Unpack the input arrays into real and imaginary parts.
        // MSVC sadly doesn't recognize the `vld2_f16`, so we load the  data as signed
        // integers of the same size and reinterpret with `vreinterpret_f16_s16` afterwards.
        int16x4x2_t a_vec = vld2_s16((short *)a_pairs);
        int16x4x2_t b_vec = vld2_s16((short *)b_pairs);
        float32x4_t a_real_vec = vcvt_f32_f16(vreinterpret_f16_s16(a_vec.val[0]));
        float32x4_t a_imag_vec = vcvt_f32_f16(vreinterpret_f16_s16(a_vec.val[1]));
        float32x4_t b_real_vec = vcvt_f32_f16(vreinterpret_f16_s16(b_vec.val[0]));
        float32x4_t b_imag_vec = vcvt_f32_f16(vreinterpret_f16_s16(b_vec.val[1]));

        // Compute the dot product:
        ab_real_vec = vfmaq_f32(ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = vfmsq_f32(ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_imag_vec, b_real_vec);

        count_pairs -= 4, a_pairs += 4, b_pairs += 4;
    }

    // Reduce horizontal sums and aggregate with the tail:
    simsimd_dot_f16c_serial(a_pairs, b_pairs, count_pairs, results);
    results[0] += vaddvq_f32(ab_real_vec);
    results[1] += vaddvq_f32(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f16c_neon(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                           simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // A nicer approach is to use `f16` arithmetic for the dot product, but that requires
    // FMLA extensions available on Arm v8.3 and later. That we can also process 16 entries
    // at once. That's how the original implementation worked, but compiling it was a nightmare :)
    float32x4_t ab_real_vec = vdupq_n_f32(0);
    float32x4_t ab_imag_vec = vdupq_n_f32(0);

    while (count_pairs >= 4) {
        // Unpack the input arrays into real and imaginary parts.
        // MSVC sadly doesn't recognize the `vld2_f16`, so we load the  data as signed
        // integers of the same size and reinterpret with `vreinterpret_f16_s16` afterwards.
        int16x4x2_t a_vec = vld2_s16((short *)a_pairs);
        int16x4x2_t b_vec = vld2_s16((short *)b_pairs);
        float32x4_t a_real_vec = vcvt_f32_f16(vreinterpret_f16_s16(a_vec.val[0]));
        float32x4_t a_imag_vec = vcvt_f32_f16(vreinterpret_f16_s16(a_vec.val[1]));
        float32x4_t b_real_vec = vcvt_f32_f16(vreinterpret_f16_s16(b_vec.val[0]));
        float32x4_t b_imag_vec = vcvt_f32_f16(vreinterpret_f16_s16(b_vec.val[1]));

        // Compute the dot product:
        ab_real_vec = vfmaq_f32(ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = vfmaq_f32(ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = vfmsq_f32(ab_imag_vec, a_imag_vec, b_real_vec);

        count_pairs -= 4, a_pairs += 4, b_pairs += 4;
    }

    // Reduce horizontal sums and aggregate with the tail:
    simsimd_vdot_f16c_serial(a_pairs, b_pairs, count_pairs, results);
    results[0] += vaddvq_f32(ab_real_vec);
    results[1] += vaddvq_f32(ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON_F16

#if SIMSIMD_TARGET_NEON_BF16
#pragma GCC push_options
#pragma GCC target("arch=armv8.6-a+simd+bf16")
#pragma clang attribute push(__attribute__((target("arch=armv8.6-a+simd+bf16"))), apply_to = function)

SIMSIMD_INTERNAL bfloat16x8_t _simsimd_partial_load_bf16x8_neon(simsimd_bf16_t const *x, simsimd_size_t n) {
    union {
        bfloat16x8_t vec;
        simsimd_bf16_t scalars[8];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[i] = x[i];
    for (; i < 8; ++i) result.scalars[i] = 0;
    return result.vec;
}

SIMSIMD_PUBLIC void simsimd_dot_bf16_neon(simsimd_bf16_t const *a_scalars, simsimd_bf16_t const *b_scalars,
                                          simsimd_size_t count_scalars, simsimd_distance_t *result) {
    bfloat16x8_t a_vec, b_vec;
    float32x4_t ab_vec = vdupq_n_f32(0);

simsimd_dot_bf16_neon_cycle:
    if (count_scalars < 4) {
        a_vec = _simsimd_partial_load_bf16x8_neon(a_scalars, count_scalars);
        b_vec = _simsimd_partial_load_bf16x8_neon(b_scalars, count_scalars);
        count_scalars = 0;
    }
    else {
        a_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)a_scalars);
        b_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)b_scalars);
        a_scalars += 4, b_scalars += 4, count_scalars -= 4;
    }
    ab_vec = vbfdotq_f32(ab_vec, a_vec, b_vec);
    if (count_scalars) goto simsimd_dot_bf16_neon_cycle;

    *result = vaddvq_f32(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_bf16c_neon(simsimd_bf16c_t const *a_pairs, simsimd_bf16c_t const *b_pairs,
                                           simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // A nicer approach is to use `bf16` arithmetic for the dot product, but that requires
    // FMLA extensions available on Arm v8.3 and later. That we can also process 16 entries
    // at once. That's how the original implementation worked, but compiling it was a nightmare :)
    float32x4_t ab_real_vec = vdupq_n_f32(0);
    float32x4_t ab_imag_vec = vdupq_n_f32(0);

    while (count_pairs >= 4) {
        // Unpack the input arrays into real and imaginary parts.
        // MSVC sadly doesn't recognize the `vld2_bf16`, so we load the  data as signed
        // integers of the same size and reinterpret with `vreinterpret_bf16_s16` afterwards.
        int16x4x2_t a_vec = vld2_s16((short const *)a_pairs);
        int16x4x2_t b_vec = vld2_s16((short const *)b_pairs);
        float32x4_t a_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(a_vec.val[0]));
        float32x4_t a_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(a_vec.val[1]));
        float32x4_t b_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[0]));
        float32x4_t b_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[1]));

        // Compute the dot product:
        ab_real_vec = vfmaq_f32(ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = vfmsq_f32(ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_imag_vec, b_real_vec);

        count_pairs -= 4, a_pairs += 4, b_pairs += 4;
    }

    // Reduce horizontal sums and aggregate with the tail:
    simsimd_dot_bf16c_serial(a_pairs, b_pairs, count_pairs, results);
    results[0] += vaddvq_f32(ab_real_vec);
    results[1] += vaddvq_f32(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_bf16c_neon(simsimd_bf16c_t const *a_pairs, simsimd_bf16c_t const *b_pairs,
                                            simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // A nicer approach is to use `bf16` arithmetic for the dot product, but that requires
    // FMLA extensions available on Arm v8.3 and later. That we can also process 16 entries
    // at once. That's how the original implementation worked, but compiling it was a nightmare :)
    float32x4_t ab_real_vec = vdupq_n_f32(0);
    float32x4_t ab_imag_vec = vdupq_n_f32(0);

    while (count_pairs >= 4) {
        // Unpack the input arrays into real and imaginary parts.
        // MSVC sadly doesn't recognize the `vld2_bf16`, so we load the  data as signed
        // integers of the same size and reinterpret with `vreinterpret_bf16_s16` afterwards.
        int16x4x2_t a_vec = vld2_s16((short const *)a_pairs);
        int16x4x2_t b_vec = vld2_s16((short const *)b_pairs);
        float32x4_t a_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(a_vec.val[0]));
        float32x4_t a_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(a_vec.val[1]));
        float32x4_t b_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[0]));
        float32x4_t b_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[1]));

        // Compute the dot product:
        ab_real_vec = vfmaq_f32(ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = vfmaq_f32(ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = vfmaq_f32(ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = vfmsq_f32(ab_imag_vec, a_imag_vec, b_real_vec);

        count_pairs -= 4, a_pairs += 4, b_pairs += 4;
    }

    // Reduce horizontal sums and aggregate with the tail:
    simsimd_vdot_bf16c_serial(a_pairs, b_pairs, count_pairs, results);
    results[0] += vaddvq_f32(ab_real_vec);
    results[1] += vaddvq_f32(ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON_BF16

#if SIMSIMD_TARGET_SVE

#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+sve")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+sve"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_f32_sve(simsimd_f32_t const *a_scalars, simsimd_f32_t const *b_scalars,
                                        simsimd_size_t count_scalars, simsimd_distance_t *result) {
    simsimd_size_t idx_scalars = 0;
    svfloat32_t ab_vec = svdup_f32(0.f);
    do {
        svbool_t pg_vec = svwhilelt_b32((unsigned int)idx_scalars, (unsigned int)count_scalars);
        svfloat32_t a_vec = svld1_f32(pg_vec, a_scalars + idx_scalars);
        svfloat32_t b_vec = svld1_f32(pg_vec, b_scalars + idx_scalars);
        ab_vec = svmla_f32_x(pg_vec, ab_vec, a_vec, b_vec);
        idx_scalars += svcntw();
    } while (idx_scalars < count_scalars);
    *result = svaddv_f32(svptrue_b32(), ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f32c_sve(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                         simsimd_size_t count_pairs, simsimd_distance_t *results) {
    simsimd_size_t idx_pairs = 0;
    svfloat32_t ab_real_vec = svdup_f32(0.f);
    svfloat32_t ab_imag_vec = svdup_f32(0.f);
    do {
        svbool_t pg_vec = svwhilelt_b32((unsigned int)idx_pairs, (unsigned int)count_pairs);
        svfloat32x2_t a_vec = svld2_f32(pg_vec, (simsimd_f32_t const *)(a_pairs + idx_pairs));
        svfloat32x2_t b_vec = svld2_f32(pg_vec, (simsimd_f32_t const *)(b_pairs + idx_pairs));
        svfloat32_t a_real_vec = svget2_f32(a_vec, 0);
        svfloat32_t a_imag_vec = svget2_f32(a_vec, 1);
        svfloat32_t b_real_vec = svget2_f32(b_vec, 0);
        svfloat32_t b_imag_vec = svget2_f32(b_vec, 1);
        ab_real_vec = svmla_f32_x(pg_vec, ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = svmls_f32_x(pg_vec, ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = svmla_f32_x(pg_vec, ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = svmla_f32_x(pg_vec, ab_imag_vec, a_imag_vec, b_real_vec);
        idx_pairs += svcntw();
    } while (idx_pairs < count_pairs);
    results[0] = svaddv_f32(svptrue_b32(), ab_real_vec);
    results[1] = svaddv_f32(svptrue_b32(), ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f32c_sve(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                          simsimd_size_t count_pairs, simsimd_distance_t *results) {
    simsimd_size_t idx_pairs = 0;
    svfloat32_t ab_real_vec = svdup_f32(0.f);
    svfloat32_t ab_imag_vec = svdup_f32(0.f);
    do {
        svbool_t pg_vec = svwhilelt_b32((unsigned int)idx_pairs, (unsigned int)count_pairs);
        svfloat32x2_t a_vec = svld2_f32(pg_vec, (simsimd_f32_t const *)(a_pairs + idx_pairs));
        svfloat32x2_t b_vec = svld2_f32(pg_vec, (simsimd_f32_t const *)(b_pairs + idx_pairs));
        svfloat32_t a_real_vec = svget2_f32(a_vec, 0);
        svfloat32_t a_imag_vec = svget2_f32(a_vec, 1);
        svfloat32_t b_real_vec = svget2_f32(b_vec, 0);
        svfloat32_t b_imag_vec = svget2_f32(b_vec, 1);
        ab_real_vec = svmla_f32_x(pg_vec, ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = svmla_f32_x(pg_vec, ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = svmla_f32_x(pg_vec, ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = svmls_f32_x(pg_vec, ab_imag_vec, a_imag_vec, b_real_vec);
        idx_pairs += svcntw();
    } while (idx_pairs < count_pairs);
    results[0] = svaddv_f32(svptrue_b32(), ab_real_vec);
    results[1] = svaddv_f32(svptrue_b32(), ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f64_sve(simsimd_f64_t const *a_scalars, simsimd_f64_t const *b_scalars,
                                        simsimd_size_t count_scalars, simsimd_distance_t *result) {
    simsimd_size_t idx_scalars = 0;
    svfloat64_t ab_vec = svdup_f64(0.);
    do {
        svbool_t pg_vec = svwhilelt_b64((unsigned int)idx_scalars, (unsigned int)count_scalars);
        svfloat64_t a_vec = svld1_f64(pg_vec, a_scalars + idx_scalars);
        svfloat64_t b_vec = svld1_f64(pg_vec, b_scalars + idx_scalars);
        ab_vec = svmla_f64_x(pg_vec, ab_vec, a_vec, b_vec);
        idx_scalars += svcntd();
    } while (idx_scalars < count_scalars);
    *result = svaddv_f64(svptrue_b32(), ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f64c_sve(simsimd_f64c_t const *a_pairs, simsimd_f64c_t const *b_pairs,
                                         simsimd_size_t count_pairs, simsimd_distance_t *results) {
    simsimd_size_t idx_pairs = 0;
    svfloat64_t ab_real_vec = svdup_f64(0.);
    svfloat64_t ab_imag_vec = svdup_f64(0.);
    do {
        svbool_t pg_vec = svwhilelt_b64((unsigned int)idx_pairs, (unsigned int)count_pairs);
        svfloat64x2_t a_vec = svld2_f64(pg_vec, (simsimd_f64_t const *)(a_pairs + idx_pairs));
        svfloat64x2_t b_vec = svld2_f64(pg_vec, (simsimd_f64_t const *)(b_pairs + idx_pairs));
        svfloat64_t a_real_vec = svget2_f64(a_vec, 0);
        svfloat64_t a_imag_vec = svget2_f64(a_vec, 1);
        svfloat64_t b_real_vec = svget2_f64(b_vec, 0);
        svfloat64_t b_imag_vec = svget2_f64(b_vec, 1);
        ab_real_vec = svmla_f64_x(pg_vec, ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = svmls_f64_x(pg_vec, ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = svmla_f64_x(pg_vec, ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = svmla_f64_x(pg_vec, ab_imag_vec, a_imag_vec, b_real_vec);
        idx_pairs += svcntd();
    } while (idx_pairs < count_pairs);
    results[0] = svaddv_f64(svptrue_b64(), ab_real_vec);
    results[1] = svaddv_f64(svptrue_b64(), ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f64c_sve(simsimd_f64c_t const *a_pairs, simsimd_f64c_t const *b_pairs,
                                          simsimd_size_t count_pairs, simsimd_distance_t *results) {
    simsimd_size_t idx_pairs = 0;
    svfloat64_t ab_real_vec = svdup_f64(0.);
    svfloat64_t ab_imag_vec = svdup_f64(0.);
    do {
        svbool_t pg_vec = svwhilelt_b64((unsigned int)idx_pairs, (unsigned int)count_pairs);
        svfloat64x2_t a_vec = svld2_f64(pg_vec, (simsimd_f64_t const *)(a_pairs + idx_pairs));
        svfloat64x2_t b_vec = svld2_f64(pg_vec, (simsimd_f64_t const *)(b_pairs + idx_pairs));
        svfloat64_t a_real_vec = svget2_f64(a_vec, 0);
        svfloat64_t a_imag_vec = svget2_f64(a_vec, 1);
        svfloat64_t b_real_vec = svget2_f64(b_vec, 0);
        svfloat64_t b_imag_vec = svget2_f64(b_vec, 1);
        ab_real_vec = svmla_f64_x(pg_vec, ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = svmla_f64_x(pg_vec, ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = svmla_f64_x(pg_vec, ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = svmls_f64_x(pg_vec, ab_imag_vec, a_imag_vec, b_real_vec);
        idx_pairs += svcntd();
    } while (idx_pairs < count_pairs);
    results[0] = svaddv_f64(svptrue_b64(), ab_real_vec);
    results[1] = svaddv_f64(svptrue_b64(), ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options

#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+sve+fp16")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+sve+fp16"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_f16_sve(simsimd_f16_t const *a_scalars, simsimd_f16_t const *b_scalars,
                                        simsimd_size_t count_scalars, simsimd_distance_t *result) {
    simsimd_size_t idx_scalars = 0;
    svfloat16_t ab_vec = svdup_f16(0);
    do {
        svbool_t pg_vec = svwhilelt_b16((unsigned int)idx_scalars, (unsigned int)count_scalars);
        svfloat16_t a_vec = svld1_f16(pg_vec, (simsimd_f16_for_arm_simd_t const *)(a_scalars + idx_scalars));
        svfloat16_t b_vec = svld1_f16(pg_vec, (simsimd_f16_for_arm_simd_t const *)(b_scalars + idx_scalars));
        ab_vec = svmla_f16_x(pg_vec, ab_vec, a_vec, b_vec);
        idx_scalars += svcnth();
    } while (idx_scalars < count_scalars);
    simsimd_f16_for_arm_simd_t ab = svaddv_f16(svptrue_b16(), ab_vec);
    *result = ab;
}

SIMSIMD_PUBLIC void simsimd_dot_f16c_sve(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                         simsimd_size_t count_pairs, simsimd_distance_t *results) {
    simsimd_size_t idx_pairs = 0;
    svfloat16_t ab_real_vec = svdup_f16(0);
    svfloat16_t ab_imag_vec = svdup_f16(0);
    do {
        svbool_t pg_vec = svwhilelt_b32((unsigned int)idx_pairs, (unsigned int)count_pairs);
        svfloat16x2_t a_vec = svld2_f16(pg_vec, (simsimd_f16_for_arm_simd_t const *)(a_pairs + idx_pairs));
        svfloat16x2_t b_vec = svld2_f16(pg_vec, (simsimd_f16_for_arm_simd_t const *)(b_pairs + idx_pairs));
        svfloat16_t a_real_vec = svget2_f16(a_vec, 0);
        svfloat16_t a_imag_vec = svget2_f16(a_vec, 1);
        svfloat16_t b_real_vec = svget2_f16(b_vec, 0);
        svfloat16_t b_imag_vec = svget2_f16(b_vec, 1);
        ab_real_vec = svmla_f16_x(pg_vec, ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = svmls_f16_x(pg_vec, ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = svmla_f16_x(pg_vec, ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = svmla_f16_x(pg_vec, ab_imag_vec, a_imag_vec, b_real_vec);
        idx_pairs += svcnth();
    } while (idx_pairs < count_pairs);
    results[0] = svaddv_f16(svptrue_b16(), ab_real_vec);
    results[1] = svaddv_f16(svptrue_b16(), ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f16c_sve(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                          simsimd_size_t count_pairs, simsimd_distance_t *results) {
    simsimd_size_t idx_pairs = 0;
    svfloat16_t ab_real_vec = svdup_f16(0);
    svfloat16_t ab_imag_vec = svdup_f16(0);
    do {
        svbool_t pg_vec = svwhilelt_b32((unsigned int)idx_pairs, (unsigned int)count_pairs);
        svfloat16x2_t a_vec = svld2_f16(pg_vec, (simsimd_f16_for_arm_simd_t const *)(a_pairs + idx_pairs));
        svfloat16x2_t b_vec = svld2_f16(pg_vec, (simsimd_f16_for_arm_simd_t const *)(b_pairs + idx_pairs));
        svfloat16_t a_real_vec = svget2_f16(a_vec, 0);
        svfloat16_t a_imag_vec = svget2_f16(a_vec, 1);
        svfloat16_t b_real_vec = svget2_f16(b_vec, 0);
        svfloat16_t b_imag_vec = svget2_f16(b_vec, 1);
        ab_real_vec = svmla_f16_x(pg_vec, ab_real_vec, a_real_vec, b_real_vec);
        ab_real_vec = svmla_f16_x(pg_vec, ab_real_vec, a_imag_vec, b_imag_vec);
        ab_imag_vec = svmla_f16_x(pg_vec, ab_imag_vec, a_real_vec, b_imag_vec);
        ab_imag_vec = svmls_f16_x(pg_vec, ab_imag_vec, a_imag_vec, b_real_vec);
        idx_pairs += svcnth();
    } while (idx_pairs < count_pairs);
    results[0] = svaddv_f16(svptrue_b16(), ab_real_vec);
    results[1] = svaddv_f16(svptrue_b16(), ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SVE
#endif // _SIMSIMD_TARGET_ARM

#if _SIMSIMD_TARGET_X86
#if SIMSIMD_TARGET_HASWELL
#pragma GCC push_options
#pragma GCC target("avx2", "f16c", "fma")
#pragma clang attribute push(__attribute__((target("avx2,f16c,fma"))), apply_to = function)

SIMSIMD_INTERNAL simsimd_f64_t _simsimd_reduce_f64x4_haswell(__m256d vec) {
    // Reduce the double-precision vector to a scalar
    // Horizontal add the first and second double-precision values, and third and fourth
    __m128d vec_low = _mm256_castpd256_pd128(vec);
    __m128d vec_high = _mm256_extractf128_pd(vec, 1);
    __m128d vec128 = _mm_add_pd(vec_low, vec_high);

    // Horizontal add again to accumulate all four values into one
    vec128 = _mm_hadd_pd(vec128, vec128);

    // Convert the final sum to a scalar double-precision value and return
    return _mm_cvtsd_f64(vec128);
}

SIMSIMD_INTERNAL simsimd_f64_t _simsimd_reduce_f32x8_haswell(__m256 vec) {
    // Convert the lower and higher 128-bit lanes of the input vector to double precision
    __m128 low_f32 = _mm256_castps256_ps128(vec);
    __m128 high_f32 = _mm256_extractf128_ps(vec, 1);

    // Convert single-precision (float) vectors to double-precision (double) vectors
    __m256d low_f64 = _mm256_cvtps_pd(low_f32);
    __m256d high_f64 = _mm256_cvtps_pd(high_f32);

    // Perform the addition in double-precision
    __m256d sum = _mm256_add_pd(low_f64, high_f64);
    return _simsimd_reduce_f64x4_haswell(sum);
}

SIMSIMD_INTERNAL simsimd_i32_t _simsimd_reduce_i32x8_haswell(__m256i vec) {
    __m128i low = _mm256_castsi256_si128(vec);
    __m128i high = _mm256_extracti128_si256(vec, 1);
    __m128i sum = _mm_add_epi32(low, high);
    sum = _mm_hadd_epi32(sum, sum);
    sum = _mm_hadd_epi32(sum, sum);
    return _mm_cvtsi128_si32(sum);
}

SIMSIMD_PUBLIC void simsimd_dot_f32_haswell(simsimd_f32_t const *a_scalars, simsimd_f32_t const *b_scalars,
                                            simsimd_size_t count_scalars, simsimd_distance_t *results) {

    __m256 ab_vec = _mm256_setzero_ps();
    simsimd_size_t idx_scalars = 0;
    for (; idx_scalars + 8 <= count_scalars; idx_scalars += 8) {
        __m256 a_vec = _mm256_loadu_ps(a_scalars + idx_scalars);
        __m256 b_vec = _mm256_loadu_ps(b_scalars + idx_scalars);
        ab_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_vec);
    }
    simsimd_f64_t ab = _simsimd_reduce_f32x8_haswell(ab_vec);
    for (; idx_scalars < count_scalars; ++idx_scalars) ab += a_scalars[idx_scalars] * b_scalars[idx_scalars];
    *results = ab;
}

SIMSIMD_PUBLIC void simsimd_dot_f32c_haswell(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                             simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // The naive approach would be to use FMA and FMS instructions on different parts of the vectors.
    // Prior to that we would need to shuffle the input vectors to separate real and imaginary parts.
    // Both operations are quite expensive, and the resulting kernel would run at 2.5 GB/s.
    // __m128 ab_real_vec = _mm_setzero_ps();
    // __m128 ab_imag_vec = _mm_setzero_ps();
    // __m256i permute_vec = _mm256_set_epi32(7, 5, 3, 1, 6, 4, 2, 0);
    // simsimd_size_t idx_pairs = 0;
    // for (; idx_pairs + 4 <= count_pairs; idx_pairs += 4) {
    //     __m256 a_vec = _mm256_loadu_ps((simsimd_f32_t const *)(a_pairs + idx_pairs));
    //     __m256 b_vec = _mm256_loadu_ps((simsimd_f32_t const *)(b_pairs + idx_pairs));
    //     __m256 a_shuffled = _mm256_permutevar8x32_ps(a_vec, permute_vec);
    //     __m256 b_shuffled = _mm256_permutevar8x32_ps(b_vec, permute_vec);
    //     __m128 a_real_vec = _mm256_extractf128_ps(a_shuffled, 0);
    //     __m128 a_imag_vec = _mm256_extractf128_ps(a_shuffled, 1);
    //     __m128 b_real_vec = _mm256_extractf128_ps(b_shuffled, 0);
    //     __m128 b_imag_vec = _mm256_extractf128_ps(b_shuffled, 1);
    //     ab_real_vec = _mm_fmadd_ps(a_real_vec, b_real_vec, ab_real_vec);
    //     ab_real_vec = _mm_fnmadd_ps(a_imag_vec, b_imag_vec, ab_real_vec);
    //     ab_imag_vec = _mm_fmadd_ps(a_real_vec, b_imag_vec, ab_imag_vec);
    //     ab_imag_vec = _mm_fmadd_ps(a_imag_vec, b_real_vec, ab_imag_vec);
    // }
    //
    // Instead, we take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors. Moreover, `XOR` can be placed after the primary loop.
    // Both operations are quite cheap, and the throughput doubles from 2.5 GB/s to 5 GB/s.
    __m256 ab_real_vec = _mm256_setzero_ps();
    __m256 ab_imag_vec = _mm256_setzero_ps();
    __m256i sign_flip_vec = _mm256_set1_epi64x(0x8000000000000000);
    __m256i swap_adjacent_vec = _mm256_set_epi8( //
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4,                              // Points to the second f32 in 128-bit lane
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4                               // Points to the second f32 in 128-bit lane
    );

    simsimd_size_t idx_pairs = 0;
    for (; idx_pairs + 4 <= count_pairs; idx_pairs += 4) {
        __m256 a_vec = _mm256_loadu_ps((simsimd_f32_t const *)(a_pairs + idx_pairs));
        __m256 b_vec = _mm256_loadu_ps((simsimd_f32_t const *)(b_pairs + idx_pairs));
        __m256 b_swapped_vec = _mm256_castsi256_ps(_mm256_shuffle_epi8(_mm256_castps_si256(b_vec), swap_adjacent_vec));
        ab_real_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_real_vec);
        ab_imag_vec = _mm256_fmadd_ps(a_vec, b_swapped_vec, ab_imag_vec);
    }

    // Flip the sign bit in every second scalar before accumulation:
    ab_real_vec = _mm256_castsi256_ps(_mm256_xor_si256(_mm256_castps_si256(ab_real_vec), sign_flip_vec));

    // Reduce horizontal sums:
    simsimd_distance_t ab_real = _simsimd_reduce_f32x8_haswell(ab_real_vec);
    simsimd_distance_t ab_imag = _simsimd_reduce_f32x8_haswell(ab_imag_vec);

    // Handle the tail:
    for (; idx_pairs != count_pairs; ++idx_pairs) {
        simsimd_f32c_t a_pair = a_pairs[idx_pairs], b_pair = b_pairs[idx_pairs];
        simsimd_f32_t ar = a_pair.real, ai = a_pair.imag, br = b_pair.real, bi = b_pair.imag;
        ab_real += ar * br - ai * bi;
        ab_imag += ar * bi + ai * br;
    }
    results[0] = ab_real;
    results[1] = ab_imag;
}

SIMSIMD_PUBLIC void simsimd_vdot_f32c_haswell(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                              simsimd_size_t count_pairs, simsimd_distance_t *results) {

    __m256 ab_real_vec = _mm256_setzero_ps();
    __m256 ab_imag_vec = _mm256_setzero_ps();
    __m256i sign_flip_vec = _mm256_set1_epi64x(0x8000000000000000);
    __m256i swap_adjacent_vec = _mm256_set_epi8( //
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4,                              // Points to the second f32 in 128-bit lane
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4                               // Points to the second f32 in 128-bit lane
    );

    simsimd_size_t idx_pairs = 0;
    for (; idx_pairs + 4 <= count_pairs; idx_pairs += 4) {
        __m256 a_vec = _mm256_loadu_ps((simsimd_f32_t const *)(a_pairs + idx_pairs));
        __m256 b_vec = _mm256_loadu_ps((simsimd_f32_t const *)(b_pairs + idx_pairs));
        ab_real_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_real_vec);
        b_vec = _mm256_castsi256_ps(_mm256_shuffle_epi8(_mm256_castps_si256(b_vec), swap_adjacent_vec));
        ab_imag_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_imag_vec);
    }

    // Flip the sign bit in every second scalar before accumulation:
    ab_imag_vec = _mm256_castsi256_ps(_mm256_xor_si256(_mm256_castps_si256(ab_imag_vec), sign_flip_vec));

    // Reduce horizontal sums:
    simsimd_distance_t ab_real = _simsimd_reduce_f32x8_haswell(ab_real_vec);
    simsimd_distance_t ab_imag = _simsimd_reduce_f32x8_haswell(ab_imag_vec);

    // Handle the tail:
    for (; idx_pairs != count_pairs; ++idx_pairs) {
        simsimd_f32c_t a_pair = a_pairs[idx_pairs], b_pair = b_pairs[idx_pairs];
        simsimd_f32_t ar = a_pair.real, ai = a_pair.imag, br = b_pair.real, bi = b_pair.imag;
        ab_real += ar * br + ai * bi;
        ab_imag += ar * bi - ai * br;
    }
    results[0] = ab_real;
    results[1] = ab_imag;
}

SIMSIMD_INTERNAL __m256 _simsimd_partial_load_f16x8_haswell(simsimd_f16_t const *a, simsimd_size_t n) {
    // In case the software emulation for `f16` scalars is enabled, the `simsimd_f16_to_f32`
    // function will run. It is extremely slow, so even for the tail, let's combine serial
    // loads and stores with vectorized math.
    union {
        __m128i vec;
        simsimd_f16_t scalars[8];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[i] = a[i];
    for (; i < 8; ++i) result.scalars[i] = 0;
    return _mm256_cvtph_ps(result.vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f16_haswell(simsimd_f16_t const *a_scalars, simsimd_f16_t const *b_scalars,
                                            simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m256 a_vec, b_vec;
    __m256 ab_vec = _mm256_setzero_ps();

simsimd_dot_f16_haswell_cycle:
    if (count_scalars < 8) {
        a_vec = _simsimd_partial_load_f16x8_haswell(a_scalars, count_scalars);
        b_vec = _simsimd_partial_load_f16x8_haswell(b_scalars, count_scalars);
        count_scalars = 0;
    }
    else {
        a_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)a_scalars));
        b_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)b_scalars));
        count_scalars -= 8, a_scalars += 8, b_scalars += 8;
    }
    // We can silence the NaNs using blends:
    //
    //     __m256 a_is_nan = _mm256_cmp_ps(a_vec, a_vec, _CMP_UNORD_Q);
    //     __m256 b_is_nan = _mm256_cmp_ps(b_vec, b_vec, _CMP_UNORD_Q);
    //     ab_vec = _mm256_blendv_ps(_mm256_fmadd_ps(a_vec, b_vec, ab_vec), ab_vec, _mm256_or_ps(a_is_nan, b_is_nan));
    //
    ab_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_vec);
    if (count_scalars) goto simsimd_dot_f16_haswell_cycle;

    *result = _simsimd_reduce_f32x8_haswell(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f16c_haswell(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                             simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // Ideally the implementation would load 256 bits worth of vector data at a time,
    // shuffle those within a register, split in halfs, and only then upcast.
    // That way, we are stepping through 32x 16-bit vector components at a time, or 16 dimensions.
    // Sadly, shuffling 16-bit entries in a YMM register is hard to implement efficiently.
    //
    // Simpler approach is to load 128 bits at a time, upcast, and then shuffle.
    // This mostly replicates the `simsimd_dot_f32c_haswell`.
    __m256 ab_real_vec = _mm256_setzero_ps();
    __m256 ab_imag_vec = _mm256_setzero_ps();
    __m256i sign_flip_vec = _mm256_set1_epi64x(0x8000000000000000);
    __m256i swap_adjacent_vec = _mm256_set_epi8( //
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4,                              // Points to the second f32 in 128-bit lane
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4                               // Points to the second f32 in 128-bit lane
    );

    while (count_pairs >= 4) {
        __m256 a_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)a_pairs));
        __m256 b_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)b_pairs));
        __m256 b_swapped_vec = _mm256_castsi256_ps(_mm256_shuffle_epi8(_mm256_castps_si256(b_vec), swap_adjacent_vec));
        ab_real_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_real_vec);
        ab_imag_vec = _mm256_fmadd_ps(a_vec, b_swapped_vec, ab_imag_vec);
        count_pairs -= 4, a_pairs += 4, b_pairs += 4;
    }

    // Flip the sign bit in every second scalar before accumulation:
    ab_real_vec = _mm256_castsi256_ps(_mm256_xor_si256(_mm256_castps_si256(ab_real_vec), sign_flip_vec));

    // Reduce horizontal sums and aggregate with the tail:
    simsimd_dot_f16c_serial(a_pairs, b_pairs, count_pairs, results);
    results[0] += _simsimd_reduce_f32x8_haswell(ab_real_vec);
    results[1] += _simsimd_reduce_f32x8_haswell(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f16c_haswell(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                              simsimd_size_t count_pairs, simsimd_distance_t *results) {

    // Ideally the implementation would load 256 bits worth of vector data at a time,
    // shuffle those within a register, split in halfs, and only then upcast.
    // That way, we are stepping through 32x 16-bit vector components at a time, or 16 dimensions.
    // Sadly, shuffling 16-bit entries in a YMM register is hard to implement efficiently.
    //
    // Simpler approach is to load 128 bits at a time, upcast, and then shuffle.
    // This mostly replicates the `simsimd_vdot_f32c_haswell`.
    __m256 ab_real_vec = _mm256_setzero_ps();
    __m256 ab_imag_vec = _mm256_setzero_ps();
    __m256i sign_flip_vec = _mm256_set1_epi64x(0x8000000000000000);
    __m256i swap_adjacent_vec = _mm256_set_epi8( //
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4,                              // Points to the second f32 in 128-bit lane
        11, 10, 9, 8,                            // Points to the third f32 in 128-bit lane
        15, 14, 13, 12,                          // Points to the fourth f32 in 128-bit lane
        3, 2, 1, 0,                              // Points to the first f32 in 128-bit lane
        7, 6, 5, 4                               // Points to the second f32 in 128-bit lane
    );

    while (count_pairs >= 4) {
        __m256 a_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)a_pairs));
        __m256 b_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)b_pairs));
        ab_real_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_real_vec);
        b_vec = _mm256_castsi256_ps(_mm256_shuffle_epi8(_mm256_castps_si256(b_vec), swap_adjacent_vec));
        ab_imag_vec = _mm256_fmadd_ps(a_vec, b_vec, ab_imag_vec);
        count_pairs -= 4, a_pairs += 4, b_pairs += 4;
    }

    // Flip the sign bit in every second scalar before accumulation:
    ab_imag_vec = _mm256_castsi256_ps(_mm256_xor_si256(_mm256_castps_si256(ab_imag_vec), sign_flip_vec));

    // Reduce horizontal sums and aggregate with the tail:
    simsimd_dot_f16c_serial(a_pairs, b_pairs, count_pairs, results);
    results[0] += _simsimd_reduce_f32x8_haswell(ab_real_vec);
    results[1] += _simsimd_reduce_f32x8_haswell(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_i8_haswell(simsimd_i8_t const *a_scalars, simsimd_i8_t const *b_scalars,
                                           simsimd_size_t count_scalars, simsimd_distance_t *result) {

    __m256i ab_i32_low_vec = _mm256_setzero_si256();
    __m256i ab_i32_high_vec = _mm256_setzero_si256();

    // AVX2 has no instructions for 8-bit signed integer dot-products,
    // but it has a weird instruction for mixed signed-unsigned 8-bit dot-product.
    // So we need to normalize the first vector to its absolute value,
    // and shift the product sign into the second vector.
    //
    //      __m256i a_i8_abs_vec = _mm256_abs_epi8(a_i8_vec);
    //      __m256i b_i8_flipped_vec = _mm256_sign_epi8(b_i8_vec, a_i8_vec);
    //      __m256i ab_i16_vec = _mm256_maddubs_epi16(a_i8_abs_vec, b_i8_flipped_vec);
    //
    // The problem with this approach, however, is the `-128` value in the second vector.
    // Flipping it's sign will do nothing, and the result will be incorrect.
    // This can easily lead to noticeable numerical errors in the final result.
    simsimd_size_t idx_scalars = 0;
    for (; idx_scalars + 32 <= count_scalars; idx_scalars += 32) {
        __m256i a_i8_vec = _mm256_lddqu_si256((__m256i const *)(a_scalars + idx_scalars));
        __m256i b_i8_vec = _mm256_lddqu_si256((__m256i const *)(b_scalars + idx_scalars));

        // Upcast `int8` to `int16`
        __m256i a_i16_low_vec = _mm256_cvtepi8_epi16(_mm256_extracti128_si256(a_i8_vec, 0));
        __m256i a_i16_high_vec = _mm256_cvtepi8_epi16(_mm256_extracti128_si256(a_i8_vec, 1));
        __m256i b_i16_low_vec = _mm256_cvtepi8_epi16(_mm256_extracti128_si256(b_i8_vec, 0));
        __m256i b_i16_high_vec = _mm256_cvtepi8_epi16(_mm256_extracti128_si256(b_i8_vec, 1));

        // Multiply and accumulate at int16 level, accumulate at `int32` level
        ab_i32_low_vec = _mm256_add_epi32(ab_i32_low_vec, _mm256_madd_epi16(a_i16_low_vec, b_i16_low_vec));
        ab_i32_high_vec = _mm256_add_epi32(ab_i32_high_vec, _mm256_madd_epi16(a_i16_high_vec, b_i16_high_vec));
    }

    // Horizontal sum across the 256-bit register
    int ab = _simsimd_reduce_i32x8_haswell(_mm256_add_epi32(ab_i32_low_vec, ab_i32_high_vec));

    // Take care of the tail:
    for (; idx_scalars < count_scalars; ++idx_scalars) ab += (int)(a_scalars[idx_scalars]) * b_scalars[idx_scalars];
    *result = ab;
}

SIMSIMD_PUBLIC void simsimd_dot_u8_haswell(simsimd_u8_t const *a_scalars, simsimd_u8_t const *b_scalars,
                                           simsimd_size_t count_scalars, simsimd_distance_t *result) {

    __m256i ab_i32_low_vec = _mm256_setzero_si256();
    __m256i ab_i32_high_vec = _mm256_setzero_si256();
    __m256i const zeros_vec = _mm256_setzero_si256();

    // AVX2 has no instructions for unsigned 8-bit integer dot-products,
    // but it has a weird instruction for mixed signed-unsigned 8-bit dot-product.
    simsimd_size_t idx_scalars = 0;
    for (; idx_scalars + 32 <= count_scalars; idx_scalars += 32) {
        __m256i a_u8_vec = _mm256_lddqu_si256((__m256i const *)(a_scalars + idx_scalars));
        __m256i b_u8_vec = _mm256_lddqu_si256((__m256i const *)(b_scalars + idx_scalars));

        // Upcast `uint8` to `int16`. Unlike the signed version, we can use the unpacking
        // instructions instead of extracts, as they are much faster and more efficient.
        __m256i a_i16_low_vec = _mm256_unpacklo_epi8(a_u8_vec, zeros_vec);
        __m256i a_i16_high_vec = _mm256_unpackhi_epi8(a_u8_vec, zeros_vec);
        __m256i b_i16_low_vec = _mm256_unpacklo_epi8(b_u8_vec, zeros_vec);
        __m256i b_i16_high_vec = _mm256_unpackhi_epi8(b_u8_vec, zeros_vec);

        // Multiply and accumulate at int16 level, accumulate at int32 level
        ab_i32_low_vec = _mm256_add_epi32(ab_i32_low_vec, _mm256_madd_epi16(a_i16_low_vec, b_i16_low_vec));
        ab_i32_high_vec = _mm256_add_epi32(ab_i32_high_vec, _mm256_madd_epi16(a_i16_high_vec, b_i16_high_vec));
    }

    // Horizontal sum across the 256-bit register
    int ab = _simsimd_reduce_i32x8_haswell(_mm256_add_epi32(ab_i32_low_vec, ab_i32_high_vec));

    // Take care of the tail:
    for (; idx_scalars < count_scalars; ++idx_scalars) ab += (int)(a_scalars[idx_scalars]) * b_scalars[idx_scalars];
    *result = ab;
}

SIMSIMD_INTERNAL __m256 _simsimd_bf16x8_to_f32x8_haswell(__m128i x) {
    // Upcasting from `bf16` to `f32` is done by shifting the `bf16` values by 16 bits to the left, like:
    return _mm256_castsi256_ps(_mm256_slli_epi32(_mm256_cvtepu16_epi32(x), 16));
}

SIMSIMD_INTERNAL __m128i _simsimd_f32x8_to_bf16x8_haswell(__m256 x) {
    // Pack the 32-bit integers into 16-bit integers.
    // This is less trivial than unpacking: https://stackoverflow.com/a/77781241/2766161
    // The best approach is to shuffle within lanes first: https://stackoverflow.com/a/49723746/2766161
    // Our shuffling mask will drop the low 2-bytes from every 4-byte word.
    __m256i trunc_elements = _mm256_shuffle_epi8(                       //
        _mm256_castps_si256(x),                                         //
        _mm256_set_epi8(                                                //
            -1, -1, -1, -1, -1, -1, -1, -1, 15, 14, 11, 10, 7, 6, 3, 2, //
            -1, -1, -1, -1, -1, -1, -1, -1, 15, 14, 11, 10, 7, 6, 3, 2  //
            ));
    __m256i ordered = _mm256_permute4x64_epi64(trunc_elements, 0x58);
    __m128i result = _mm256_castsi256_si128(ordered);
    return result;
}

SIMSIMD_INTERNAL __m128i _simsimd_partial_load_bf16x8_haswell(simsimd_bf16_t const *a, simsimd_size_t n) {
    // In case the software emulation for `bf16` scalars is enabled, the `simsimd_bf16_to_f32`
    // function will run. It is extremely slow, so even for the tail, let's combine serial
    // loads and stores with vectorized math.
    union {
        __m128i vec;
        simsimd_bf16_t scalars[8];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[i] = a[i];
    for (; i < 8; ++i) result.scalars[i] = 0;
    return result.vec;
}

SIMSIMD_PUBLIC void simsimd_dot_bf16_haswell(simsimd_bf16_t const *a_scalars, simsimd_bf16_t const *b_scalars,
                                             simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m128i a_vec, b_vec;
    __m256 ab_vec = _mm256_setzero_ps();

simsimd_dot_bf16_haswell_cycle:
    if (count_scalars < 8) {
        a_vec = _simsimd_partial_load_bf16x8_haswell(a_scalars, count_scalars);
        b_vec = _simsimd_partial_load_bf16x8_haswell(b_scalars, count_scalars);
        count_scalars = 0;
    }
    else {
        a_vec = _mm_lddqu_si128((__m128i const *)a_scalars);
        b_vec = _mm_lddqu_si128((__m128i const *)b_scalars);
        a_scalars += 8, b_scalars += 8, count_scalars -= 8;
    }
    ab_vec = _mm256_fmadd_ps(_simsimd_bf16x8_to_f32x8_haswell(a_vec), _simsimd_bf16x8_to_f32x8_haswell(b_vec), ab_vec);
    if (count_scalars) goto simsimd_dot_bf16_haswell_cycle;

    *result = _simsimd_reduce_f32x8_haswell(ab_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_HASWELL

#if SIMSIMD_TARGET_SKYLAKE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "avx512bw", "bmi2")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,avx512bw,bmi2"))), apply_to = function)

SIMSIMD_INTERNAL simsimd_f64_t _simsimd_reduce_f32x16_skylake(__m512 a) {
    __m512 x = _mm512_add_ps(a, _mm512_shuffle_f32x4(a, a, _MM_SHUFFLE(0, 0, 3, 2)));
    __m128 r = _mm512_castps512_ps128(_mm512_add_ps(x, _mm512_shuffle_f32x4(x, x, _MM_SHUFFLE(0, 0, 0, 1))));
    r = _mm_hadd_ps(r, r);
    return _mm_cvtss_f32(_mm_hadd_ps(r, r));
}

SIMSIMD_INTERNAL __m512 _simsimd_bf16x16_to_f32x16_skylake(__m256i a) {
    // Upcasting from `bf16` to `f32` is done by shifting the `bf16` values by 16 bits to the left, like:
    return _mm512_castsi512_ps(_mm512_slli_epi32(_mm512_cvtepu16_epi32(a), 16));
}

SIMSIMD_INTERNAL __m256i _simsimd_f32x16_to_bf16x16_skylake(__m512 a) {
    // Add 2^15 and right shift 16 to do round-nearest
    __m512i x = _mm512_srli_epi32(_mm512_add_epi32(_mm512_castps_si512(a), _mm512_set1_epi32(1 << 15)), 16);
    return _mm512_cvtepi32_epi16(x);
}

SIMSIMD_PUBLIC void simsimd_dot_f32_skylake(simsimd_f32_t const *a_scalars, simsimd_f32_t const *b_scalars,
                                            simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m512 a_vec, b_vec;
    __m512 ab_vec = _mm512_setzero();

simsimd_dot_f32_skylake_cycle:
    if (count_scalars < 16) {
        __mmask16 mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, count_scalars);
        a_vec = _mm512_maskz_loadu_ps(mask, a_scalars);
        b_vec = _mm512_maskz_loadu_ps(mask, b_scalars);
        count_scalars = 0;
    }
    else {
        a_vec = _mm512_loadu_ps(a_scalars);
        b_vec = _mm512_loadu_ps(b_scalars);
        a_scalars += 16, b_scalars += 16, count_scalars -= 16;
    }
    ab_vec = _mm512_fmadd_ps(a_vec, b_vec, ab_vec);
    if (count_scalars) goto simsimd_dot_f32_skylake_cycle;

    *result = _simsimd_reduce_f32x16_skylake(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f64_skylake(simsimd_f64_t const *a_scalars, simsimd_f64_t const *b_scalars,
                                            simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m512d a_vec, b_vec;
    __m512d ab_vec = _mm512_setzero_pd();

simsimd_dot_f64_skylake_cycle:
    if (count_scalars < 8) {
        __mmask8 mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, count_scalars);
        a_vec = _mm512_maskz_loadu_pd(mask, a_scalars);
        b_vec = _mm512_maskz_loadu_pd(mask, b_scalars);
        count_scalars = 0;
    }
    else {
        a_vec = _mm512_loadu_pd(a_scalars);
        b_vec = _mm512_loadu_pd(b_scalars);
        a_scalars += 8, b_scalars += 8, count_scalars -= 8;
    }
    ab_vec = _mm512_fmadd_pd(a_vec, b_vec, ab_vec);
    if (count_scalars) goto simsimd_dot_f64_skylake_cycle;

    *result = _mm512_reduce_add_pd(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f32c_skylake(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                             simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512 a_vec, b_vec;
    __m512 ab_real_vec = _mm512_setzero();
    __m512 ab_imag_vec = _mm512_setzero();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi64(0x8000000000000000);
simsimd_dot_f32c_skylake_cycle:
    if (count_pairs < 8) {
        __mmask16 mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_ps(mask, a_pairs);
        b_vec = _mm512_maskz_loadu_ps(mask, b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_ps(a_pairs);
        b_vec = _mm512_loadu_ps(b_pairs);
        a_pairs += 8, b_pairs += 8, count_pairs -= 8;
    }
    ab_real_vec = _mm512_fmadd_ps(b_vec, a_vec, ab_real_vec);
    b_vec = _mm512_permute_ps(b_vec, 0xB1); //? Swap adjacent entries within each pair
    ab_imag_vec = _mm512_fmadd_ps(b_vec, a_vec, ab_imag_vec);
    if (count_pairs) goto simsimd_dot_f32c_skylake_cycle;

    // Flip the sign bit in every second scalar before accumulation:
    ab_real_vec = _mm512_castsi512_ps(_mm512_xor_si512(_mm512_castps_si512(ab_real_vec), sign_flip_vec));

    // Reduce horizontal sums:
    results[0] = _simsimd_reduce_f32x16_skylake(ab_real_vec);
    results[1] = _simsimd_reduce_f32x16_skylake(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f32c_skylake(simsimd_f32c_t const *a_pairs, simsimd_f32c_t const *b_pairs,
                                              simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512 a_vec, b_vec;
    __m512 ab_real_vec = _mm512_setzero();
    __m512 ab_imag_vec = _mm512_setzero();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi64(0x8000000000000000);
    __m512i const swap_adjacent_vec = _mm512_set_epi8(                  //
        59, 58, 57, 56, 63, 62, 61, 60, 51, 50, 49, 48, 55, 54, 53, 52, // 4th 128-bit lane
        43, 42, 41, 40, 47, 46, 45, 44, 35, 34, 33, 32, 39, 38, 37, 36, // 3rd 128-bit lane
        27, 26, 25, 24, 31, 30, 29, 28, 19, 18, 17, 16, 23, 22, 21, 20, // 2nd 128-bit lane
        11, 10, 9, 8, 15, 14, 13, 12, 3, 2, 1, 0, 7, 6, 5, 4            // 1st 128-bit lane
    );
simsimd_vdot_f32c_skylake_cycle:
    if (count_pairs < 8) {
        __mmask16 mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_ps(mask, (simsimd_f32_t const *)a_pairs);
        b_vec = _mm512_maskz_loadu_ps(mask, (simsimd_f32_t const *)b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_ps((simsimd_f32_t const *)a_pairs);
        b_vec = _mm512_loadu_ps((simsimd_f32_t const *)b_pairs);
        a_pairs += 8, b_pairs += 8, count_pairs -= 8;
    }
    ab_real_vec = _mm512_fmadd_ps(a_vec, b_vec, ab_real_vec);
    b_vec = _mm512_permute_ps(b_vec, 0xB1); //? Swap adjacent entries within each pair
    ab_imag_vec = _mm512_fmadd_ps(a_vec, b_vec, ab_imag_vec);
    if (count_pairs) goto simsimd_vdot_f32c_skylake_cycle;

    // Flip the sign bit in every second scalar before accumulation:
    ab_imag_vec = _mm512_castsi512_ps(_mm512_xor_si512(_mm512_castps_si512(ab_imag_vec), sign_flip_vec));

    // Reduce horizontal sums:
    results[0] = _simsimd_reduce_f32x16_skylake(ab_real_vec);
    results[1] = _simsimd_reduce_f32x16_skylake(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f64c_skylake(simsimd_f64c_t const *a_pairs, simsimd_f64c_t const *b_pairs,
                                             simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512d a_vec, b_vec;
    __m512d ab_real_vec = _mm512_setzero_pd();
    __m512d ab_imag_vec = _mm512_setzero_pd();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set_epi64(                                     //
        0x8000000000000000, 0x0000000000000000, 0x8000000000000000, 0x0000000000000000, //
        0x8000000000000000, 0x0000000000000000, 0x8000000000000000, 0x0000000000000000  //
    );
simsimd_dot_f64c_skylake_cycle:
    if (count_pairs < 4) {
        __mmask8 mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_pd(mask, a_pairs);
        b_vec = _mm512_maskz_loadu_pd(mask, b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_pd(a_pairs);
        b_vec = _mm512_loadu_pd(b_pairs);
        a_pairs += 4, b_pairs += 4, count_pairs -= 4;
    }
    ab_real_vec = _mm512_fmadd_pd(b_vec, a_vec, ab_real_vec);
    b_vec = _mm512_permute_pd(b_vec, 0x55); //? Same as 0b01010101.
    ab_imag_vec = _mm512_fmadd_pd(b_vec, a_vec, ab_imag_vec);
    if (count_pairs) goto simsimd_dot_f64c_skylake_cycle;

    // Flip the sign bit in every second scalar before accumulation:
    ab_real_vec = _mm512_castsi512_pd(_mm512_xor_si512(_mm512_castpd_si512(ab_real_vec), sign_flip_vec));

    // Reduce horizontal sums:
    results[0] = _mm512_reduce_add_pd(ab_real_vec);
    results[1] = _mm512_reduce_add_pd(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f64c_skylake(simsimd_f64c_t const *a_pairs, simsimd_f64c_t const *b_pairs,
                                              simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512d a_vec, b_vec;
    __m512d ab_real_vec = _mm512_setzero_pd();
    __m512d ab_imag_vec = _mm512_setzero_pd();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set_epi64(                                     //
        0x8000000000000000, 0x0000000000000000, 0x8000000000000000, 0x0000000000000000, //
        0x8000000000000000, 0x0000000000000000, 0x8000000000000000, 0x0000000000000000  //
    );
simsimd_vdot_f64c_skylake_cycle:
    if (count_pairs < 4) {
        __mmask8 mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_pd(mask, (simsimd_f32_t const *)a_pairs);
        b_vec = _mm512_maskz_loadu_pd(mask, (simsimd_f32_t const *)b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_pd((simsimd_f32_t const *)a_pairs);
        b_vec = _mm512_loadu_pd((simsimd_f32_t const *)b_pairs);
        a_pairs += 4, b_pairs += 4, count_pairs -= 4;
    }
    ab_real_vec = _mm512_fmadd_pd(a_vec, b_vec, ab_real_vec);
    b_vec = _mm512_permute_pd(b_vec, 0x55); //? Same as 0b01010101.
    ab_imag_vec = _mm512_fmadd_pd(a_vec, b_vec, ab_imag_vec);
    if (count_pairs) goto simsimd_vdot_f64c_skylake_cycle;

    // Flip the sign bit in every second scalar before accumulation:
    ab_imag_vec = _mm512_castsi512_pd(_mm512_xor_si512(_mm512_castpd_si512(ab_imag_vec), sign_flip_vec));

    // Reduce horizontal sums:
    results[0] = _mm512_reduce_add_pd(ab_real_vec);
    results[1] = _mm512_reduce_add_pd(ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SKYLAKE

#if SIMSIMD_TARGET_GENOA
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512bw", "avx512bf16")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512bw,avx512bf16"))), \
                             apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_bf16_genoa(simsimd_bf16_t const *a_scalars, simsimd_bf16_t const *b_scalars,
                                           simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m512i a_i16_vec, b_i16_vec;
    __m512 ab_vec = _mm512_setzero_ps();

simsimd_dot_bf16_genoa_cycle:
    if (count_scalars < 32) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_scalars);
        a_i16_vec = _mm512_maskz_loadu_epi16(mask, a_scalars);
        b_i16_vec = _mm512_maskz_loadu_epi16(mask, b_scalars);
        count_scalars = 0;
    }
    else {
        a_i16_vec = _mm512_loadu_epi16(a_scalars);
        b_i16_vec = _mm512_loadu_epi16(b_scalars);
        a_scalars += 32, b_scalars += 32, count_scalars -= 32;
    }
    ab_vec = _mm512_dpbf16_ps(ab_vec, (__m512bh)(a_i16_vec), (__m512bh)(b_i16_vec));
    if (count_scalars) goto simsimd_dot_bf16_genoa_cycle;

    *result = _simsimd_reduce_f32x16_skylake(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_bf16c_genoa(simsimd_bf16c_t const *a_pairs, simsimd_bf16c_t const *b_pairs,
                                            simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512i a_vec, b_vec;
    __m512 ab_real_vec = _mm512_setzero_ps();
    __m512 ab_imag_vec = _mm512_setzero_ps();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi32(0x80000000);
    __m512i const swap_adjacent_vec = _mm512_set_epi8(                  //
        61, 60, 63, 62, 57, 56, 59, 58, 53, 52, 55, 54, 49, 48, 51, 50, // 4th 128-bit lane
        45, 44, 47, 46, 41, 40, 43, 42, 37, 36, 39, 38, 33, 32, 35, 34, // 3rd 128-bit lane
        29, 28, 31, 30, 25, 24, 27, 26, 21, 20, 23, 22, 17, 16, 19, 18, // 2nd 128-bit lane
        13, 12, 15, 14, 9, 8, 11, 10, 5, 4, 7, 6, 1, 0, 3, 2            // 1st 128-bit lane
    );

simsimd_dot_bf16c_genoa_cycle:
    if (count_pairs < 16) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_epi16(mask, (simsimd_i16_t const *)a_pairs);
        b_vec = _mm512_maskz_loadu_epi16(mask, (simsimd_i16_t const *)b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_epi16((simsimd_i16_t const *)a_pairs);
        b_vec = _mm512_loadu_epi16((simsimd_i16_t const *)b_pairs);
        a_pairs += 16, b_pairs += 16, count_pairs -= 16;
    }
    ab_real_vec = _mm512_dpbf16_ps(ab_real_vec, (__m512bh)(_mm512_xor_si512(b_vec, sign_flip_vec)), (__m512bh)(a_vec));
    ab_imag_vec =
        _mm512_dpbf16_ps(ab_imag_vec, (__m512bh)(_mm512_shuffle_epi8(b_vec, swap_adjacent_vec)), (__m512bh)(a_vec));
    if (count_pairs) goto simsimd_dot_bf16c_genoa_cycle;

    // Reduce horizontal sums:
    results[0] = _simsimd_reduce_f32x16_skylake(ab_real_vec);
    results[1] = _simsimd_reduce_f32x16_skylake(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_bf16c_genoa(simsimd_bf16c_t const *a_pairs, simsimd_bf16c_t const *b_pairs,
                                             simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512i a_vec, b_vec;
    __m512 ab_real_vec = _mm512_setzero_ps();
    __m512 ab_imag_vec = _mm512_setzero_ps();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi32(0x80000000);
    __m512i const swap_adjacent_vec = _mm512_set_epi8(                  //
        61, 60, 63, 62, 57, 56, 59, 58, 53, 52, 55, 54, 49, 48, 51, 50, // 4th 128-bit lane
        45, 44, 47, 46, 41, 40, 43, 42, 37, 36, 39, 38, 33, 32, 35, 34, // 3rd 128-bit lane
        29, 28, 31, 30, 25, 24, 27, 26, 21, 20, 23, 22, 17, 16, 19, 18, // 2nd 128-bit lane
        13, 12, 15, 14, 9, 8, 11, 10, 5, 4, 7, 6, 1, 0, 3, 2            // 1st 128-bit lane
    );

simsimd_dot_bf16c_genoa_cycle:
    if (count_pairs < 16) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_epi16(mask, (simsimd_i16_t const *)a_pairs);
        b_vec = _mm512_maskz_loadu_epi16(mask, (simsimd_i16_t const *)b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_epi16((simsimd_i16_t const *)a_pairs);
        b_vec = _mm512_loadu_epi16((simsimd_i16_t const *)b_pairs);
        a_pairs += 16, b_pairs += 16, count_pairs -= 16;
    }
    ab_real_vec = _mm512_dpbf16_ps(ab_real_vec, (__m512bh)(a_vec), (__m512bh)(b_vec));
    a_vec = _mm512_xor_si512(a_vec, sign_flip_vec);
    b_vec = _mm512_shuffle_epi8(b_vec, swap_adjacent_vec);
    ab_imag_vec = _mm512_dpbf16_ps(ab_imag_vec, (__m512bh)(a_vec), (__m512bh)(b_vec));
    if (count_pairs) goto simsimd_dot_bf16c_genoa_cycle;

    // Reduce horizontal sums:
    results[0] = _simsimd_reduce_f32x16_skylake(ab_real_vec);
    results[1] = _simsimd_reduce_f32x16_skylake(ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_GENOA

#if SIMSIMD_TARGET_SAPPHIRE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512bw", "avx512fp16")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512bw,avx512fp16"))), \
                             apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_f16_sapphire(simsimd_f16_t const *a_scalars, simsimd_f16_t const *b_scalars,
                                             simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m512i a_i16_vec, b_i16_vec;
    __m512h ab_vec = _mm512_setzero_ph();

simsimd_dot_f16_sapphire_cycle:
    if (count_scalars < 32) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_scalars);
        a_i16_vec = _mm512_maskz_loadu_epi16(mask, a_scalars);
        b_i16_vec = _mm512_maskz_loadu_epi16(mask, b_scalars);
        count_scalars = 0;
    }
    else {
        a_i16_vec = _mm512_loadu_epi16(a_scalars);
        b_i16_vec = _mm512_loadu_epi16(b_scalars);
        a_scalars += 32, b_scalars += 32, count_scalars -= 32;
    }
    ab_vec = _mm512_fmadd_ph(_mm512_castsi512_ph(a_i16_vec), _mm512_castsi512_ph(b_i16_vec), ab_vec);
    if (count_scalars) goto simsimd_dot_f16_sapphire_cycle;

    *result = _mm512_reduce_add_ph(ab_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_f16c_sapphire(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                              simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512i a_vec, b_vec;
    __m512h ab_real_vec = _mm512_setzero_ph();
    __m512h ab_imag_vec = _mm512_setzero_ph();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi32(0x80000000);
    __m512i const swap_adjacent_vec = _mm512_set_epi8(                  //
        61, 60, 63, 62, 57, 56, 59, 58, 53, 52, 55, 54, 49, 48, 51, 50, // 4th 128-bit lane
        45, 44, 47, 46, 41, 40, 43, 42, 37, 36, 39, 38, 33, 32, 35, 34, // 3rd 128-bit lane
        29, 28, 31, 30, 25, 24, 27, 26, 21, 20, 23, 22, 17, 16, 19, 18, // 2nd 128-bit lane
        13, 12, 15, 14, 9, 8, 11, 10, 5, 4, 7, 6, 1, 0, 3, 2            // 1st 128-bit lane
    );

simsimd_dot_f16c_sapphire_cycle:
    if (count_pairs < 16) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_epi16(mask, a_pairs);
        b_vec = _mm512_maskz_loadu_epi16(mask, b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_epi16(a_pairs);
        b_vec = _mm512_loadu_epi16(b_pairs);
        a_pairs += 16, b_pairs += 16, count_pairs -= 16;
    }
    // TODO: Consider using `_mm512_fmaddsub` and `_mm512_fcmadd_pch`
    ab_real_vec = _mm512_fmadd_ph(_mm512_castsi512_ph(_mm512_xor_si512(b_vec, sign_flip_vec)),
                                  _mm512_castsi512_ph(a_vec), ab_real_vec);
    ab_imag_vec = _mm512_fmadd_ph(_mm512_castsi512_ph(_mm512_shuffle_epi8(b_vec, swap_adjacent_vec)),
                                  _mm512_castsi512_ph(a_vec), ab_imag_vec);
    if (count_pairs) goto simsimd_dot_f16c_sapphire_cycle;

    // Reduce horizontal sums:
    // TODO: Optimize this with tree-like reductions
    results[0] = _mm512_reduce_add_ph(ab_real_vec);
    results[1] = _mm512_reduce_add_ph(ab_imag_vec);
}

SIMSIMD_PUBLIC void simsimd_vdot_f16c_sapphire(simsimd_f16c_t const *a_pairs, simsimd_f16c_t const *b_pairs,
                                               simsimd_size_t count_pairs, simsimd_distance_t *results) {
    __m512i a_vec, b_vec;
    __m512h ab_real_vec = _mm512_setzero_ph();
    __m512h ab_imag_vec = _mm512_setzero_ph();

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi32(0x80000000);
    __m512i const swap_adjacent_vec = _mm512_set_epi8(                  //
        61, 60, 63, 62, 57, 56, 59, 58, 53, 52, 55, 54, 49, 48, 51, 50, // 4th 128-bit lane
        45, 44, 47, 46, 41, 40, 43, 42, 37, 36, 39, 38, 33, 32, 35, 34, // 3rd 128-bit lane
        29, 28, 31, 30, 25, 24, 27, 26, 21, 20, 23, 22, 17, 16, 19, 18, // 2nd 128-bit lane
        13, 12, 15, 14, 9, 8, 11, 10, 5, 4, 7, 6, 1, 0, 3, 2            // 1st 128-bit lane
    );

simsimd_dot_f16c_sapphire_cycle:
    if (count_pairs < 16) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_pairs * 2);
        a_vec = _mm512_maskz_loadu_epi16(mask, a_pairs);
        b_vec = _mm512_maskz_loadu_epi16(mask, b_pairs);
        count_pairs = 0;
    }
    else {
        a_vec = _mm512_loadu_epi16(a_pairs);
        b_vec = _mm512_loadu_epi16(b_pairs);
        a_pairs += 16, b_pairs += 16, count_pairs -= 16;
    }
    // TODO: Consider using `_mm512_fmaddsub` and `_mm512_fcmadd_pch`
    ab_real_vec = _mm512_fmadd_ph(_mm512_castsi512_ph(a_vec), _mm512_castsi512_ph(b_vec), ab_real_vec);
    a_vec = _mm512_xor_si512(a_vec, sign_flip_vec);
    b_vec = _mm512_shuffle_epi8(b_vec, swap_adjacent_vec);
    ab_imag_vec = _mm512_fmadd_ph(_mm512_castsi512_ph(a_vec), _mm512_castsi512_ph(b_vec), ab_imag_vec);
    if (count_pairs) goto simsimd_dot_f16c_sapphire_cycle;

    // Reduce horizontal sums:
    results[0] = _mm512_reduce_add_ph(ab_real_vec);
    results[1] = _mm512_reduce_add_ph(ab_imag_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SAPPHIRE

#if SIMSIMD_TARGET_ICE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512bw", "avx512vnni")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512bw,avx512vnni"))), \
                             apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_i8_ice(simsimd_i8_t const *a_scalars, simsimd_i8_t const *b_scalars,
                                       simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m512i a_i16_vec, b_i16_vec;
    __m512i ab_i32_vec = _mm512_setzero_si512();

simsimd_dot_i8_ice_cycle:
    if (count_scalars < 32) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, count_scalars);
        a_i16_vec = _mm512_cvtepi8_epi16(_mm256_maskz_loadu_epi8(mask, a_scalars));
        b_i16_vec = _mm512_cvtepi8_epi16(_mm256_maskz_loadu_epi8(mask, b_scalars));
        count_scalars = 0;
    }
    else {
        a_i16_vec = _mm512_cvtepi8_epi16(_mm256_lddqu_si256((__m256i const *)a_scalars));
        b_i16_vec = _mm512_cvtepi8_epi16(_mm256_lddqu_si256((__m256i const *)b_scalars));
        a_scalars += 32, b_scalars += 32, count_scalars -= 32;
    }
    // Unfortunately we can't use the `_mm512_dpbusd_epi32` intrinsics here either,
    // as it's asymmetric with respect to the sign of the input arguments:
    //      Signed(ZeroExtend16(a_scalars.byte[4*j]) * SignExtend16(b_scalars.byte[4*j]))
    // So we have to use the `_mm512_dpwssd_epi32` intrinsics instead, upcasting
    // to 16-bit beforehand.
    ab_i32_vec = _mm512_dpwssd_epi32(ab_i32_vec, a_i16_vec, b_i16_vec);
    if (count_scalars) goto simsimd_dot_i8_ice_cycle;

    *result = _mm512_reduce_add_epi32(ab_i32_vec);
}

SIMSIMD_PUBLIC void simsimd_dot_u8_ice(simsimd_u8_t const *a_scalars, simsimd_u8_t const *b_scalars,
                                       simsimd_size_t count_scalars, simsimd_distance_t *result) {
    __m512i a_u8_vec, b_u8_vec;
    __m512i a_i16_low_vec, a_i16_high_vec, b_i16_low_vec, b_i16_high_vec;
    __m512i ab_i32_low_vec = _mm512_setzero_si512();
    __m512i ab_i32_high_vec = _mm512_setzero_si512();
    __m512i const zeros_vec = _mm512_setzero_si512();

simsimd_dot_u8_ice_cycle:
    if (count_scalars < 64) {
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, count_scalars);
        a_u8_vec = _mm512_maskz_loadu_epi8(mask, a_scalars);
        b_u8_vec = _mm512_maskz_loadu_epi8(mask, b_scalars);
        count_scalars = 0;
    }
    else {
        a_u8_vec = _mm512_loadu_si512(a_scalars);
        b_u8_vec = _mm512_loadu_si512(b_scalars);
        a_scalars += 64, b_scalars += 64, count_scalars -= 64;
    }

    // Upcast `uint8` to `int16`. Unlike the signed version, we can use the unpacking
    // instructions instead of extracts, as they are much faster and more efficient.
    a_i16_low_vec = _mm512_unpacklo_epi8(a_u8_vec, zeros_vec);
    a_i16_high_vec = _mm512_unpackhi_epi8(a_u8_vec, zeros_vec);
    b_i16_low_vec = _mm512_unpacklo_epi8(b_u8_vec, zeros_vec);
    b_i16_high_vec = _mm512_unpackhi_epi8(b_u8_vec, zeros_vec);
    // Unfortunately we can't use the `_mm512_dpbusd_epi32` intrinsics here either,
    // as it's asymmetric with respect to the sign of the input arguments:
    //      Signed(ZeroExtend16(a.byte[4*j]) * SignExtend16(b.byte[4*j]))
    // So we have to use the `_mm512_dpwssd_epi32` intrinsics instead, upcasting
    // to 16-bit beforehand.
    ab_i32_low_vec = _mm512_dpwssd_epi32(ab_i32_low_vec, a_i16_low_vec, b_i16_low_vec);
    ab_i32_high_vec = _mm512_dpwssd_epi32(ab_i32_high_vec, a_i16_high_vec, b_i16_high_vec);
    if (count_scalars) goto simsimd_dot_u8_ice_cycle;

    *result = _mm512_reduce_add_epi32(_mm512_add_epi32(ab_i32_low_vec, ab_i32_high_vec));
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_ICE

#if SIMSIMD_TARGET_SIERRA
#pragma GCC push_options
#pragma GCC target("avx2", "bmi2", "avx2vnni")
#pragma clang attribute push(__attribute__((target("avx2,bmi2,avx2vnni"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_dot_i8_sierra(simsimd_i8_t const *a_scalars, simsimd_i8_t const *b_scalars,
                                          simsimd_size_t count_scalars, simsimd_distance_t *result) {

    __m256i ab_i32_vec = _mm256_setzero_si256();
    simsimd_size_t idx_scalars = 0;
    for (; idx_scalars + 32 <= count_scalars; idx_scalars += 32) {
        __m256i a_i8_vec = _mm256_lddqu_si256((__m256i const *)(a_scalars + idx_scalars));
        __m256i b_i8_vec = _mm256_lddqu_si256((__m256i const *)(b_scalars + idx_scalars));
        ab_i32_vec = _mm256_dpbssds_epi32(ab_i32_vec, a_i8_vec, b_i8_vec);
    }

    // Further reduce to a single sum for each vector
    int ab = _simsimd_reduce_i32x8_haswell(ab_i32_vec);

    // Take care of the tail:
    for (; idx_scalars < count_scalars; ++idx_scalars) ab += (int)(a_scalars[idx_scalars]) * b_scalars[idx_scalars];
    *result = ab;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SIERRA
#endif // _SIMSIMD_TARGET_X86

#ifdef __cplusplus
}
#endif

#endif
