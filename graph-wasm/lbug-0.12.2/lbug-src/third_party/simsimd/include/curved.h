/**
 *  @file       curved.h
 *  @brief      SIMD-accelerated Similarity Measures for curved spaces.
 *  @author     Ash Vardanian
 *  @date       August 27, 2024
 *
 *  Contains:
 *  - Mahalanobis distance
 *  - Bilinear form multiplication
 *  - Bilinear form multiplication over complex numbers
 *
 *  For datatypes:
 *  - 64-bit floating point numbers
 *  - 32-bit floating point numbers
 *  - 16-bit floating point numbers
 *  - 16-bit brain-floating point numbers
 *
 *  For hardware architectures:
 *  - Arm: NEON
 *  - x86: Haswell, Ice Lake, Skylake, Genoa, Sapphire
 *
 *  Most kernels in this file are designed for BLAS level 2 operations, where the operands are
 *  a combination of matrices and vectors, generally forming a chain of multiplications.
 *  Most kernels exploit the fact that matrix multiplication is associative, and the order of
 *  operations can be changed to minimize the number of operations: `(A * B) * C = A * (B * C)`.
 *  To optimize the performance, we minimize the number of memory accesses, and maximize the
 *  number of arithmetic operations, by using SIMD instructions.
 *
 *  When A and C are vectors, and B is a matrix, we can load every element in B just once, and
 *  reuse it for every element in A and C.
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 */
#ifndef SIMSIMD_CURVED_H
#define SIMSIMD_CURVED_H

#include "types.h"

#include "dot.h"     // `_simsimd_partial_load_f16x4_neon` and friends
#include "spatial.h" // `_simsimd_substract_bf16x32_genoa`

#ifdef __cplusplus
extern "C" {
#endif

// clang-format off

/*  Serial backends for all numeric types.
 *  By default they use 32-bit arithmetic, unless the arguments themselves contain 64-bit floats.
 *  For double-precision computation check out the "*_accurate" variants of those "*_serial" functions.
 */
SIMSIMD_PUBLIC void simsimd_bilinear_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_f64_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f64c_serial(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_f64c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_f64_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f32c_serial(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_f32c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16c_serial(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_f16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16c_serial(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_bf16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);

/*  Double-precision serial backends for all numeric types.
 *  For single-precision computation check out the "*_serial" counterparts of those "*_accurate" functions.
 */
SIMSIMD_PUBLIC void simsimd_bilinear_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f32c_accurate(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_f32c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16c_accurate(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_f16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16c_accurate(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_bf16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);

/*  SIMD-powered backends for Arm NEON, mostly using 32-bit arithmetic over 128-bit words.
 *  By far the most portable backend, covering most Arm v8 devices, over a billion phones, and almost all
 *  server CPUs produced before 2023.
 */
SIMSIMD_PUBLIC void simsimd_bilinear_f32_neon(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f32c_neon(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_f32c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f32_neon(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16_neon(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16c_neon(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_f16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_neon(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16_neon(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16c_neon(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_bf16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_neon(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);

/*  SIMD-powered backends for AVX2 CPUs of Haswell generation and newer, using 32-bit arithmetic over 256-bit words.
 *  First demonstrated in 2011, at least one Haswell-based processor was still being sold in 2022 — the Pentium G3420.
 *  Practically all modern x86 CPUs support AVX2, FMA, and F16C, making it a perfect baseline for SIMD algorithms.
 *  On other hand, there is no need to implement AVX2 versions of `f32` and `f64` functions, as those are
 *  properly vectorized by recent compilers.
 */
SIMSIMD_PUBLIC void simsimd_bilinear_f16_haswell(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_haswell(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16_haswell(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_haswell(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);

/*  SIMD-powered backends for various generations of AVX512 CPUs.
 *  Skylake is handy, as it supports masked loads and other operations, avoiding the need for the tail loop.
 *  Ice Lake added VNNI, VPOPCNTDQ, IFMA, VBMI, VAES, GFNI, VBMI2, BITALG, VPCLMULQDQ, and other extensions for integral operations.
 *  Sapphire Rapids added tiled matrix operations, but we are most interested in the new mixed-precision FMA instructions.
 */
SIMSIMD_PUBLIC void simsimd_bilinear_f64_skylake(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_f64_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f64c_skylake(simsimd_f64c_t const* a, simsimd_f64c_t const* b, simsimd_f64c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f64_skylake(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_f64_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f32_skylake(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f32c_skylake(simsimd_f32c_t const* a, simsimd_f32c_t const* b, simsimd_f32c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f32_skylake(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_f32_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16_genoa(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_bf16c_genoa(simsimd_bf16c_t const* a, simsimd_bf16c_t const* b, simsimd_bf16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_genoa(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_bf16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16_sapphire(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_bilinear_f16c_sapphire(simsimd_f16c_t const* a, simsimd_f16c_t const* b, simsimd_f16c_t const* c, simsimd_size_t n, simsimd_distance_t* results);
SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_sapphire(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_f16_t const* c, simsimd_size_t n, simsimd_distance_t* result);
// clang-format on

#define SIMSIMD_MAKE_BILINEAR(name, input_type, accumulator_type, load_and_convert)                              \
    SIMSIMD_PUBLIC void simsimd_bilinear_##input_type##_##name(                                                  \
        simsimd_##input_type##_t const *a, simsimd_##input_type##_t const *b, simsimd_##input_type##_t const *c, \
        simsimd_size_t n, simsimd_distance_t *result) {                                                          \
        simsimd_##accumulator_type##_t sum = 0;                                                                  \
        for (simsimd_size_t i = 0; i != n; ++i) {                                                                \
            simsimd_##accumulator_type##_t cb_j = 0;                                                             \
            simsimd_##accumulator_type##_t a_i = load_and_convert(a + i);                                        \
            for (simsimd_size_t j = 0; j != n; ++j) {                                                            \
                simsimd_##accumulator_type##_t b_j = load_and_convert(b + j);                                    \
                simsimd_##accumulator_type##_t c_ij = load_and_convert(c + i * n + j);                           \
                cb_j += c_ij * b_j;                                                                              \
            }                                                                                                    \
            sum += a_i * cb_j;                                                                                   \
        }                                                                                                        \
        *result = (simsimd_distance_t)sum;                                                                       \
    }

#define SIMSIMD_MAKE_COMPLEX_BILINEAR(name, input_type, accumulator_type, load_and_convert)                \
    SIMSIMD_PUBLIC void simsimd_bilinear_##input_type##_##name(                                            \
        simsimd_##input_type##_t const *a_pairs, simsimd_##input_type##_t const *b_pairs,                  \
        simsimd_##input_type##_t const *c_pairs, simsimd_size_t n, simsimd_distance_t *results) {          \
        simsimd_##accumulator_type##_t sum_real = 0;                                                       \
        simsimd_##accumulator_type##_t sum_imag = 0;                                                       \
        for (simsimd_size_t i = 0; i != n; ++i) {                                                          \
            simsimd_##accumulator_type##_t cb_j_real = 0;                                                  \
            simsimd_##accumulator_type##_t cb_j_imag = 0;                                                  \
            simsimd_##accumulator_type##_t a_i_real = load_and_convert(&(a_pairs + i)->real);              \
            simsimd_##accumulator_type##_t a_i_imag = load_and_convert(&(a_pairs + i)->imag);              \
            for (simsimd_size_t j = 0; j != n; ++j) {                                                      \
                simsimd_##accumulator_type##_t b_j_real = load_and_convert(&(b_pairs + j)->real);          \
                simsimd_##accumulator_type##_t b_j_imag = load_and_convert(&(b_pairs + j)->imag);          \
                simsimd_##accumulator_type##_t c_ij_real = load_and_convert(&(c_pairs + i * n + j)->real); \
                simsimd_##accumulator_type##_t c_ij_imag = load_and_convert(&(c_pairs + i * n + j)->imag); \
                /* Complex multiplication: (c_ij * b_j) */                                                 \
                cb_j_real += c_ij_real * b_j_real - c_ij_imag * b_j_imag;                                  \
                cb_j_imag += c_ij_real * b_j_imag + c_ij_imag * b_j_real;                                  \
            }                                                                                              \
            /* Complex multiplication: (a_i * cb_j) */                                                     \
            sum_real += a_i_real * cb_j_real - a_i_imag * cb_j_imag;                                       \
            sum_imag += a_i_real * cb_j_imag + a_i_imag * cb_j_real;                                       \
        }                                                                                                  \
        results[0] = (simsimd_distance_t)sum_real;                                                         \
        results[1] = (simsimd_distance_t)sum_imag;                                                         \
    }

#define SIMSIMD_MAKE_MAHALANOBIS(name, input_type, accumulator_type, load_and_convert)                           \
    SIMSIMD_PUBLIC void simsimd_mahalanobis_##input_type##_##name(                                               \
        simsimd_##input_type##_t const *a, simsimd_##input_type##_t const *b, simsimd_##input_type##_t const *c, \
        simsimd_size_t n, simsimd_distance_t *result) {                                                          \
        simsimd_##accumulator_type##_t sum = 0;                                                                  \
        for (simsimd_size_t i = 0; i != n; ++i) {                                                                \
            simsimd_##accumulator_type##_t cdiff_j = 0;                                                          \
            simsimd_##accumulator_type##_t diff_i = load_and_convert(a + i) - load_and_convert(b + i);           \
            for (simsimd_size_t j = 0; j != n; ++j) {                                                            \
                simsimd_##accumulator_type##_t diff_j = load_and_convert(a + j) - load_and_convert(b + j);       \
                simsimd_##accumulator_type##_t c_ij = load_and_convert(c + i * n + j);                           \
                cdiff_j += c_ij * diff_j;                                                                        \
            }                                                                                                    \
            sum += diff_i * cdiff_j;                                                                             \
        }                                                                                                        \
        *result = (simsimd_distance_t)SIMSIMD_SQRT(sum);                                                         \
    }

SIMSIMD_MAKE_BILINEAR(serial, f64, f64, SIMSIMD_DEREFERENCE)          // simsimd_bilinear_f64_serial
SIMSIMD_MAKE_COMPLEX_BILINEAR(serial, f64c, f64, SIMSIMD_DEREFERENCE) // simsimd_bilinear_f64c_serial
SIMSIMD_MAKE_MAHALANOBIS(serial, f64, f64, SIMSIMD_DEREFERENCE)       // simsimd_mahalanobis_f64_serial

SIMSIMD_MAKE_BILINEAR(serial, f32, f32, SIMSIMD_DEREFERENCE)          // simsimd_bilinear_f32_serial
SIMSIMD_MAKE_COMPLEX_BILINEAR(serial, f32c, f32, SIMSIMD_DEREFERENCE) // simsimd_bilinear_f32c_serial
SIMSIMD_MAKE_MAHALANOBIS(serial, f32, f32, SIMSIMD_DEREFERENCE)       // simsimd_mahalanobis_f32_serial

SIMSIMD_MAKE_BILINEAR(serial, f16, f32, SIMSIMD_F16_TO_F32)          // simsimd_bilinear_f16_serial
SIMSIMD_MAKE_COMPLEX_BILINEAR(serial, f16c, f32, SIMSIMD_F16_TO_F32) // simsimd_bilinear_f16c_serial
SIMSIMD_MAKE_MAHALANOBIS(serial, f16, f32, SIMSIMD_F16_TO_F32)       // simsimd_mahalanobis_f16_serial

SIMSIMD_MAKE_BILINEAR(serial, bf16, f32, SIMSIMD_BF16_TO_F32)          // simsimd_bilinear_bf16_serial
SIMSIMD_MAKE_COMPLEX_BILINEAR(serial, bf16c, f32, SIMSIMD_BF16_TO_F32) // simsimd_bilinear_bf16c_serial
SIMSIMD_MAKE_MAHALANOBIS(serial, bf16, f32, SIMSIMD_BF16_TO_F32)       // simsimd_mahalanobis_bf16_serial

SIMSIMD_MAKE_BILINEAR(accurate, f32, f64, SIMSIMD_DEREFERENCE)          // simsimd_bilinear_f32_accurate
SIMSIMD_MAKE_COMPLEX_BILINEAR(accurate, f32c, f64, SIMSIMD_DEREFERENCE) // simsimd_bilinear_f32c_accurate
SIMSIMD_MAKE_MAHALANOBIS(accurate, f32, f64, SIMSIMD_DEREFERENCE)       // simsimd_mahalanobis_f32_accurate

SIMSIMD_MAKE_BILINEAR(accurate, f16, f64, SIMSIMD_F16_TO_F32)          // simsimd_bilinear_f16_accurate
SIMSIMD_MAKE_COMPLEX_BILINEAR(accurate, f16c, f64, SIMSIMD_F16_TO_F32) // simsimd_bilinear_f16c_accurate
SIMSIMD_MAKE_MAHALANOBIS(accurate, f16, f64, SIMSIMD_F16_TO_F32)       // simsimd_mahalanobis_f16_accurate

SIMSIMD_MAKE_BILINEAR(accurate, bf16, f64, SIMSIMD_BF16_TO_F32)          // simsimd_bilinear_bf16_accurate
SIMSIMD_MAKE_COMPLEX_BILINEAR(accurate, bf16c, f64, SIMSIMD_BF16_TO_F32) // simsimd_bilinear_bf16c_accurate
SIMSIMD_MAKE_MAHALANOBIS(accurate, bf16, f64, SIMSIMD_BF16_TO_F32)       // simsimd_mahalanobis_bf16_accurate

#if _SIMSIMD_TARGET_ARM
#if SIMSIMD_TARGET_NEON
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_f32_neon(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_f32_t const *c,
                                              simsimd_size_t n, simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    for (simsimd_size_t i = 0; i != n; ++i) {
        float32x4_t a_vec = vdupq_n_f32(a[i]);
        float32x4_t cb_j_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 4 <= n; j += 4) {
            float32x4_t b_vec = vld1q_f32(b + j);
            float32x4_t c_vec = vld1q_f32(c + i * n + j);
            cb_j_vec = vmlaq_f32(cb_j_vec, b_vec, c_vec);
        }
        sum_vec = vmlaq_f32(sum_vec, a_vec, cb_j_vec);
    }

    // Handle the tail of every row
    simsimd_f64_t sum = vaddvq_f32(sum_vec);
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t cb_j = 0;
            for (simsimd_size_t j = tail_start; j != n; ++j) cb_j += b[j] * c[i * n + j];
            sum += a[i] * cb_j;
        }
    }

    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_f32_neon(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_f32_t const *c,
                                                 simsimd_size_t n, simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    for (simsimd_size_t i = 0; i != n; ++i) {
        float32x4_t diff_i_vec = vdupq_n_f32(a[i] - b[i]);
        float32x4_t cdiff_j_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 4 <= n; j += 4) {
            float32x4_t diff_j_vec = vsubq_f32(vld1q_f32(a + j), vld1q_f32(b + j));
            float32x4_t c_vec = vld1q_f32(c + i * n + j);
            cdiff_j_vec = vmlaq_f32(cdiff_j_vec, diff_j_vec, c_vec);
        }

        sum_vec = vmlaq_f32(sum_vec, diff_i_vec, cdiff_j_vec);
    }

    // Handle the tail of every row
    simsimd_f64_t sum = vaddvq_f32(sum_vec);
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t diff_i = a[i] - b[i];
            simsimd_f32_t cdiff_j = 0;
            for (simsimd_size_t j = tail_start; j != n; ++j) {
                simsimd_f32_t diff_j = a[j] - b[j];
                cdiff_j += diff_j * c[i * n + j];
            }
            sum += diff_i * cdiff_j;
        }
    }

    *result = _simsimd_sqrt_f64_neon(sum);
}

SIMSIMD_PUBLIC void simsimd_bilinear_f32c_neon(simsimd_f32c_t const *a, simsimd_f32c_t const *b,
                                               simsimd_f32c_t const *c, simsimd_size_t n, simsimd_distance_t *results) {
    simsimd_f32_t sum_real = 0;
    simsimd_f32_t sum_imag = 0;
    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32c_t a_i = a[i];
        simsimd_f32c_t cb_j;
        float32x4_t cb_j_real_vec = vdupq_n_f32(0);
        float32x4_t cb_j_imag_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 4 <= n; j += 4) {
            // Unpack the input arrays into real and imaginary parts:
            float32x4x2_t b_vec = vld2q_f32((simsimd_f32_t const *)(b + j));
            float32x4x2_t c_vec = vld2q_f32((simsimd_f32_t const *)(c + i * n + j));
            float32x4_t b_real_vec = b_vec.val[0];
            float32x4_t b_imag_vec = b_vec.val[1];
            float32x4_t c_real_vec = c_vec.val[0];
            float32x4_t c_imag_vec = c_vec.val[1];

            // Compute the dot product:
            cb_j_real_vec = vfmaq_f32(cb_j_real_vec, c_real_vec, b_real_vec);
            cb_j_real_vec = vfmsq_f32(cb_j_real_vec, c_imag_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f32(cb_j_imag_vec, c_real_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f32(cb_j_imag_vec, c_imag_vec, b_real_vec);
        }
        cb_j.real = vaddvq_f32(cb_j_real_vec);
        cb_j.imag = vaddvq_f32(cb_j_imag_vec);
        sum_real += a_i.real * cb_j.real - a_i.imag * cb_j.imag;
        sum_imag += a_i.real * cb_j.imag + a_i.imag * cb_j.real;
    }

    // Handle the tail of every row
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32c_t a_i = a[i];
            simsimd_f32c_t cb_j = {0, 0};
            for (simsimd_size_t j = tail_start; j != n; ++j) {
                simsimd_f32c_t b_j = b[j];
                simsimd_f32c_t c_ij = c[i * n + j];
                cb_j.real += b_j.real * c_ij.real - b_j.imag * c_ij.imag;
                cb_j.imag += b_j.real * c_ij.imag + b_j.imag * c_ij.real;
            }
            sum_real += a_i.real * cb_j.real - a_i.imag * cb_j.imag;
            sum_imag += a_i.real * cb_j.imag + a_i.imag * cb_j.real;
        }
    }

    results[0] = sum_real;
    results[1] = sum_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON

#if SIMSIMD_TARGET_NEON_F16
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd+fp16")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd+fp16"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_f16_neon(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_f16_t const *c,
                                              simsimd_size_t n, simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    for (simsimd_size_t i = 0; i != n; ++i) {
        // MSVC doesn't recognize `vdup_n_f16` as a valid intrinsic
        float32x4_t a_vec = vcvt_f32_f16(vreinterpret_f16_s16(vdup_n_s16(*(short const *)(a + i))));
        float32x4_t cb_j_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 4 <= n; j += 4) {
            float32x4_t b_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)(b + j)));
            float32x4_t c_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)(c + i * n + j)));
            cb_j_vec = vmlaq_f32(cb_j_vec, b_vec, c_vec);
        }
        sum_vec = vmlaq_f32(sum_vec, a_vec, cb_j_vec);
    }

    // Handle the tail of every row
    simsimd_f64_t sum = vaddvq_f32(sum_vec);
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t a_i = vaddvq_f32(vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(a + i, 1)));
            float32x4_t b_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(b + tail_start, tail_length));
            float32x4_t c_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(c + i * n + tail_start, tail_length));
            simsimd_f32_t cb_j = vaddvq_f32(vmulq_f32(b_vec, c_vec));
            sum += a_i * cb_j;
        }
    }

    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_neon(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_f16_t const *c,
                                                 simsimd_size_t n, simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    for (simsimd_size_t i = 0; i != n; ++i) {
        // MSVC doesn't recognize `vdup_n_f16` as a valid intrinsic
        float32x4_t a_i_vec = vcvt_f32_f16(vreinterpret_f16_s16(vdup_n_s16(*(short const *)(a + i))));
        float32x4_t b_i_vec = vcvt_f32_f16(vreinterpret_f16_s16(vdup_n_s16(*(short const *)(b + i))));
        float32x4_t diff_i_vec = vsubq_f32(a_i_vec, b_i_vec);
        float32x4_t cdiff_j_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 4 <= n; j += 4) {
            float32x4_t a_j_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)(a + j)));
            float32x4_t b_j_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)(b + j)));
            float32x4_t diff_j_vec = vsubq_f32(a_j_vec, b_j_vec);
            float32x4_t c_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)(c + i * n + j)));
            cdiff_j_vec = vmlaq_f32(cdiff_j_vec, diff_j_vec, c_vec);
        }
        sum_vec = vmlaq_f32(sum_vec, diff_i_vec, cdiff_j_vec);
    }

    // Handle the tail of every row
    simsimd_f32_t sum = vaddvq_f32(sum_vec);
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t a_i = vaddvq_f32(vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(a + i, 1)));
            simsimd_f32_t b_i = vaddvq_f32(vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(b + i, 1)));
            simsimd_f32_t diff_i = a_i - b_i;
            float32x4_t a_j_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(a + tail_start, tail_length));
            float32x4_t b_j_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(b + tail_start, tail_length));
            float32x4_t diff_j_vec = vsubq_f32(a_j_vec, b_j_vec);
            float32x4_t c_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(c + i * n + tail_start, tail_length));
            simsimd_f32_t cdiff_j = vaddvq_f32(vmulq_f32(diff_j_vec, c_vec));
            sum += diff_i * cdiff_j;
        }
    }

    *result = _simsimd_sqrt_f32_neon(sum);
}

SIMSIMD_INTERNAL simsimd_f32_t _simsimd_reduce_f16x8_neon(float16x8_t vec) {
    // Split the 8-element vector into two 4-element vectors
    float16x4_t low = vget_low_f16(vec);   // Lower 4 elements
    float16x4_t high = vget_high_f16(vec); // Upper 4 elements

    // Add the lower and upper parts
    float16x4_t sum = vadd_f16(low, high);

    // Perform pairwise addition to reduce 4 elements to 2, then to 1
    sum = vpadd_f16(sum, sum); // First reduction: 4 -> 2
    sum = vpadd_f16(sum, sum); // Second reduction: 2 -> 1

    // Convert the remaining half-precision value to single-precision and return
    return vgetq_lane_f32(vcvt_f32_f16(sum), 0);
}

SIMSIMD_INTERNAL float16x8x2_t _simsimd_partial_load_f16x8x2_neon(simsimd_f16c_t const *x, simsimd_size_t n) {
    union {
        float16x8x2_t vecs;
        simsimd_f16_t scalars[2][8];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[0][i] = x[i].real, result.scalars[1][i] = x[i].imag;
    for (; i < 8; ++i) result.scalars[0][i] = 0, result.scalars[1][i] = 0;
    return result.vecs;
}

SIMSIMD_PUBLIC void simsimd_bilinear_f16c_neon(simsimd_f16c_t const *a, simsimd_f16c_t const *b,
                                               simsimd_f16c_t const *c, simsimd_size_t n, simsimd_distance_t *results) {
    simsimd_f32_t sum_real = 0;
    simsimd_f32_t sum_imag = 0;
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32c_t a_i = {simsimd_f16_to_f32(&a[i].real), simsimd_f16_to_f32(&a[i].imag)};
        float16x8_t cb_j_real_vec = vdupq_n_f16(0);
        float16x8_t cb_j_imag_vec = vdupq_n_f16(0);
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            // Unpack the input arrays into real and imaginary parts:
            float16x8x2_t b_vec = vld2q_f16((float16_t const *)(b + j));
            float16x8x2_t c_vec = vld2q_f16((float16_t const *)(c + i * n + j));
            float16x8_t b_real_vec = b_vec.val[0];
            float16x8_t b_imag_vec = b_vec.val[1];
            float16x8_t c_real_vec = c_vec.val[0];
            float16x8_t c_imag_vec = c_vec.val[1];

            // Compute the dot product:
            cb_j_real_vec = vfmaq_f16(cb_j_real_vec, c_real_vec, b_real_vec);
            cb_j_real_vec = vfmsq_f16(cb_j_real_vec, c_imag_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f16(cb_j_imag_vec, c_real_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f16(cb_j_imag_vec, c_imag_vec, b_real_vec);
        }
        // Handle row tails
        if (tail_length) {
            // Unpack the input arrays into real and imaginary parts:
            float16x8x2_t b_vec = _simsimd_partial_load_f16x8x2_neon(b + tail_start, tail_length);
            float16x8x2_t c_vec = _simsimd_partial_load_f16x8x2_neon(c + i * n + tail_start, tail_length);
            float16x8_t b_real_vec = b_vec.val[0];
            float16x8_t b_imag_vec = b_vec.val[1];
            float16x8_t c_real_vec = c_vec.val[0];
            float16x8_t c_imag_vec = c_vec.val[1];

            // Compute the dot product:
            cb_j_real_vec = vfmaq_f16(cb_j_real_vec, c_real_vec, b_real_vec);
            cb_j_real_vec = vfmsq_f16(cb_j_real_vec, c_imag_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f16(cb_j_imag_vec, c_real_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f16(cb_j_imag_vec, c_imag_vec, b_real_vec);
        }

        simsimd_f32c_t cb_j;
        cb_j.real = _simsimd_reduce_f16x8_neon(cb_j_real_vec);
        cb_j.imag = _simsimd_reduce_f16x8_neon(cb_j_imag_vec);
        sum_real += a_i.real * cb_j.real - a_i.imag * cb_j.imag;
        sum_imag += a_i.real * cb_j.imag + a_i.imag * cb_j.real;
    }

    results[0] = sum_real;
    results[1] = sum_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON_F16

#if SIMSIMD_TARGET_NEON_BF16
#pragma GCC push_options
#pragma GCC target("arch=armv8.6-a+simd+bf16")
#pragma clang attribute push(__attribute__((target("arch=armv8.6-a+simd+bf16"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_bf16_neon(simsimd_bf16_t const *a, simsimd_bf16_t const *b,
                                               simsimd_bf16_t const *c, simsimd_size_t n, simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    for (simsimd_size_t i = 0; i != n; ++i) {
        float32x4_t a_vec = vdupq_n_f32(simsimd_bf16_to_f32(a + i));
        float32x4_t cb_j_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            bfloat16x8_t b_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)(b + j));
            bfloat16x8_t c_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)(c + i * n + j));
            cb_j_vec = vbfdotq_f32(cb_j_vec, b_vec, c_vec);
        }
        sum_vec = vmlaq_f32(sum_vec, a_vec, cb_j_vec);
    }

    // Handle the tail of every row
    simsimd_f64_t sum = vaddvq_f32(sum_vec);
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t a_i = simsimd_bf16_to_f32(a + i);
            bfloat16x8_t b_vec = _simsimd_partial_load_bf16x8_neon(b + tail_start, tail_length);
            bfloat16x8_t c_vec = _simsimd_partial_load_bf16x8_neon(c + i * n + tail_start, tail_length);
            simsimd_f32_t cb_j = vaddvq_f32(vbfdotq_f32(vdupq_n_f32(0), b_vec, c_vec));
            sum += a_i * cb_j;
        }
    }

    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_neon(simsimd_bf16_t const *a, simsimd_bf16_t const *b,
                                                  simsimd_bf16_t const *c, simsimd_size_t n,
                                                  simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32_t a_i = simsimd_bf16_to_f32(a + i);
        simsimd_f32_t b_i = simsimd_bf16_to_f32(b + i);
        float32x4_t diff_i_vec = vdupq_n_f32(a_i - b_i);
        float32x4_t cdiff_j_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            bfloat16x8_t a_j_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)(a + j));
            bfloat16x8_t b_j_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)(b + j));

            // Arm NEON does not have a native subtraction instruction for `bf16`,
            // so we need to convert to `f32` first, subtract, and only then get back to `bf16`
            // for multiplication.
            float32x4_t a_j_vec_high = vcvt_f32_bf16(vget_high_bf16(a_j_vec));
            float32x4_t a_j_vec_low = vcvt_f32_bf16(vget_low_bf16(a_j_vec));
            float32x4_t b_j_vec_high = vcvt_f32_bf16(vget_high_bf16(b_j_vec));
            float32x4_t b_j_vec_low = vcvt_f32_bf16(vget_low_bf16(b_j_vec));
            float32x4_t diff_j_vec_high = vsubq_f32(a_j_vec_high, b_j_vec_high);
            float32x4_t diff_j_vec_low = vsubq_f32(a_j_vec_low, b_j_vec_low);
            bfloat16x8_t diff_j_vec = vcombine_bf16(vcvt_bf16_f32(diff_j_vec_low), vcvt_bf16_f32(diff_j_vec_high));

            bfloat16x8_t c_vec = vld1q_bf16((simsimd_bf16_for_arm_simd_t const *)(c + i * n + j));
            cdiff_j_vec = vbfdotq_f32(cdiff_j_vec, diff_j_vec, c_vec);
        }
        sum_vec = vmlaq_f32(sum_vec, diff_i_vec, cdiff_j_vec);
    }

    // Handle the tail of every row
    simsimd_f32_t sum = vaddvq_f32(sum_vec);
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t a_i = simsimd_bf16_to_f32(a + i);
            simsimd_f32_t b_i = simsimd_bf16_to_f32(b + i);
            simsimd_f32_t diff_i = a_i - b_i;
            bfloat16x8_t a_j_vec = _simsimd_partial_load_bf16x8_neon(a + tail_start, tail_length);
            bfloat16x8_t b_j_vec = _simsimd_partial_load_bf16x8_neon(b + tail_start, tail_length);

            // Again, upcast for subtraction
            float32x4_t a_j_vec_high = vcvt_f32_bf16(vget_high_bf16(a_j_vec));
            float32x4_t a_j_vec_low = vcvt_f32_bf16(vget_low_bf16(a_j_vec));
            float32x4_t b_j_vec_high = vcvt_f32_bf16(vget_high_bf16(b_j_vec));
            float32x4_t b_j_vec_low = vcvt_f32_bf16(vget_low_bf16(b_j_vec));
            float32x4_t diff_j_vec_high = vsubq_f32(a_j_vec_high, b_j_vec_high);
            float32x4_t diff_j_vec_low = vsubq_f32(a_j_vec_low, b_j_vec_low);
            bfloat16x8_t diff_j_vec = vcombine_bf16(vcvt_bf16_f32(diff_j_vec_low), vcvt_bf16_f32(diff_j_vec_high));

            bfloat16x8_t c_vec = _simsimd_partial_load_bf16x8_neon(c + i * n + tail_start, tail_length);
            simsimd_f32_t cdiff_j = vaddvq_f32(vbfdotq_f32(vdupq_n_f32(0), diff_j_vec, c_vec));
            sum += diff_i * cdiff_j;
        }
    }

    *result = _simsimd_sqrt_f32_neon(sum);
}

SIMSIMD_INTERNAL int16x4x2_t _simsimd_partial_load_bf16x4x2_neon(simsimd_bf16c_t const *x, simsimd_size_t n) {
    union {
        int16x4x2_t vec;
        simsimd_bf16_t scalars[2][4];
    } result;
    simsimd_size_t i = 0;
    for (; i < n; ++i) result.scalars[0][i] = x[i].real, result.scalars[1][i] = x[i].imag;
    for (; i < 4; ++i) result.scalars[1][i] = 0, result.scalars[1][i] = 0;
    return result.vec;
}

SIMSIMD_PUBLIC void simsimd_bilinear_bf16c_neon(simsimd_bf16c_t const *a, simsimd_bf16c_t const *b,
                                                simsimd_bf16c_t const *c, simsimd_size_t n,
                                                simsimd_distance_t *results) {
    simsimd_f32_t sum_real = 0;
    simsimd_f32_t sum_imag = 0;
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32c_t a_i = {simsimd_bf16_to_f32(&a[i].real), simsimd_bf16_to_f32(&a[i].imag)};
        // A nicer approach is to use `bf16` arithmetic for the dot product, but that requires
        // FMLA extensions available on Arm v8.3 and later. That we can also process 16 entries
        // at once. That's how the original implementation worked, but compiling it was a nightmare :)
        float32x4_t cb_j_real_vec = vdupq_n_f32(0);
        float32x4_t cb_j_imag_vec = vdupq_n_f32(0);
        for (simsimd_size_t j = 0; j + 4 <= n; j += 4) {
            // Unpack the input arrays into real and imaginary parts.
            // MSVC sadly doesn't recognize the `vld2_bf16`, so we load the  data as signed
            // integers of the same size and reinterpret with `vreinterpret_bf16_s16` afterwards.
            int16x4x2_t b_vec = vld2_s16((short const *)(b + j));
            int16x4x2_t c_vec = vld2_s16((short const *)(c + i * n + j));
            float32x4_t b_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[0]));
            float32x4_t b_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[1]));
            float32x4_t c_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(c_vec.val[0]));
            float32x4_t c_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(c_vec.val[1]));

            // Compute the dot product:
            cb_j_real_vec = vfmaq_f32(cb_j_real_vec, c_real_vec, b_real_vec);
            cb_j_real_vec = vfmsq_f32(cb_j_real_vec, c_imag_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f32(cb_j_imag_vec, c_real_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f32(cb_j_imag_vec, c_imag_vec, b_real_vec);
        }
        // Handle row tails
        if (tail_length) {
            // Unpack the input arrays into real and imaginary parts:
            int16x4x2_t b_vec = _simsimd_partial_load_bf16x4x2_neon(b + tail_start, tail_length);
            int16x4x2_t c_vec = _simsimd_partial_load_bf16x4x2_neon(c + i * n + tail_start, tail_length);
            float32x4_t b_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[0]));
            float32x4_t b_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(b_vec.val[1]));
            float32x4_t c_real_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(c_vec.val[0]));
            float32x4_t c_imag_vec = vcvt_f32_bf16(vreinterpret_bf16_s16(c_vec.val[1]));

            // Compute the dot product:
            cb_j_real_vec = vfmaq_f32(cb_j_real_vec, c_real_vec, b_real_vec);
            cb_j_real_vec = vfmsq_f32(cb_j_real_vec, c_imag_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f32(cb_j_imag_vec, c_real_vec, b_imag_vec);
            cb_j_imag_vec = vfmaq_f32(cb_j_imag_vec, c_imag_vec, b_real_vec);
        }

        simsimd_f32c_t cb_j;
        cb_j.real = vaddvq_f32(cb_j_real_vec);
        cb_j.imag = vaddvq_f32(cb_j_imag_vec);
        sum_real += a_i.real * cb_j.real - a_i.imag * cb_j.imag;
        sum_imag += a_i.real * cb_j.imag + a_i.imag * cb_j.real;
    }

    results[0] = sum_real;
    results[1] = sum_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON_BF16

#endif // _SIMSIMD_TARGET_ARM

#if _SIMSIMD_TARGET_X86
#if SIMSIMD_TARGET_HASWELL
#pragma GCC push_options
#pragma GCC target("avx2", "f16c", "fma")
#pragma clang attribute push(__attribute__((target("avx2,f16c,fma"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_f16_haswell(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_f16_t const *c,
                                                 simsimd_size_t n, simsimd_distance_t *result) {
    __m256 sum_vec = _mm256_setzero_ps();
    for (simsimd_size_t i = 0; i != n; ++i) {
        __m256 a_vec = _mm256_cvtph_ps(_mm_set1_epi16(*(short const *)(a + i)));
        __m256 cb_j_vec = _mm256_setzero_ps();
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            __m256 b_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)(b + j)));
            __m256 c_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)(c + i * n + j)));
            cb_j_vec = _mm256_fmadd_ps(b_vec, c_vec, cb_j_vec);
        }
        sum_vec = _mm256_fmadd_ps(a_vec, cb_j_vec, sum_vec);
    }

    // Handle the tail of every row
    simsimd_f32_t sum = _simsimd_reduce_f32x8_haswell(sum_vec);
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t a_i = _mm256_cvtss_f32(_mm256_cvtph_ps(_mm_set1_epi16(*(short const *)(a + i))));
            __m256 b_vec = _simsimd_partial_load_f16x8_haswell(b + tail_start, tail_length);
            __m256 c_vec = _simsimd_partial_load_f16x8_haswell(c + i * n + tail_start, tail_length);
            simsimd_f32_t cb_j = _simsimd_reduce_f32x8_haswell(_mm256_mul_ps(b_vec, c_vec));
            sum += a_i * cb_j;
        }
    }

    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_haswell(simsimd_f16_t const *a, simsimd_f16_t const *b,
                                                    simsimd_f16_t const *c, simsimd_size_t n,
                                                    simsimd_distance_t *result) {
    __m256 sum_vec = _mm256_setzero_ps();
    for (simsimd_size_t i = 0; i != n; ++i) {
        __m256 diff_i_vec = _mm256_sub_ps(                            //
            _mm256_cvtph_ps(_mm_set1_epi16(*(short const *)(a + i))), //
            _mm256_cvtph_ps(_mm_set1_epi16(*(short const *)(b + i))));
        __m256 cdiff_j_vec = _mm256_setzero_ps();
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            __m256 diff_j_vec = _mm256_sub_ps( //
                _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)(a + j))),
                _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)(b + j))));
            __m256 c_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)(c + i * n + j)));
            cdiff_j_vec = _mm256_fmadd_ps(diff_j_vec, c_vec, cdiff_j_vec);
        }
        sum_vec = _mm256_fmadd_ps(diff_i_vec, cdiff_j_vec, sum_vec);
    }

    // Handle the tail of every row
    simsimd_f32_t sum = _simsimd_reduce_f32x8_haswell(sum_vec);
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t diff_i = _mm256_cvtss_f32(_mm256_sub_ps(        //
                _mm256_cvtph_ps(_mm_set1_epi16(*(short const *)(a + i))), //
                _mm256_cvtph_ps(_mm_set1_epi16(*(short const *)(b + i)))));
            __m256 diff_j_vec = _mm256_sub_ps( //
                _simsimd_partial_load_f16x8_haswell(a + tail_start, tail_length),
                _simsimd_partial_load_f16x8_haswell(b + tail_start, tail_length));
            __m256 c_vec = _simsimd_partial_load_f16x8_haswell(c + i * n + tail_start, tail_length);
            simsimd_f32_t cdiff_j = _simsimd_reduce_f32x8_haswell(_mm256_mul_ps(diff_j_vec, c_vec));
            sum += diff_i * cdiff_j;
        }
    }

    *result = _simsimd_sqrt_f32_haswell(sum);
}

SIMSIMD_PUBLIC void simsimd_bilinear_bf16_haswell(simsimd_bf16_t const *a, simsimd_bf16_t const *b,
                                                  simsimd_bf16_t const *c, simsimd_size_t n,
                                                  simsimd_distance_t *result) {
    __m256 sum_vec = _mm256_setzero_ps();
    for (simsimd_size_t i = 0; i != n; ++i) {
        // The `simsimd_bf16_to_f32` is cheaper than `_simsimd_bf16x8_to_f32x8_haswell`
        __m256 a_vec = _mm256_set1_ps(simsimd_bf16_to_f32(a + i));
        __m256 cb_j_vec = _mm256_setzero_ps();
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            __m256 b_vec = _simsimd_bf16x8_to_f32x8_haswell(_mm_lddqu_si128((__m128i const *)(b + j)));
            __m256 c_vec = _simsimd_bf16x8_to_f32x8_haswell(_mm_lddqu_si128((__m128i const *)(c + i * n + j)));
            cb_j_vec = _mm256_fmadd_ps(b_vec, c_vec, cb_j_vec);
        }
        sum_vec = _mm256_fmadd_ps(a_vec, cb_j_vec, sum_vec);
    }

    // Handle the tail of every row
    simsimd_f32_t sum = _simsimd_reduce_f32x8_haswell(sum_vec);
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t a_i = simsimd_bf16_to_f32(a + i);
            __m256 b_vec = _simsimd_bf16x8_to_f32x8_haswell( //
                _simsimd_partial_load_bf16x8_haswell(b + tail_start, tail_length));
            __m256 c_vec = _simsimd_bf16x8_to_f32x8_haswell( //
                _simsimd_partial_load_bf16x8_haswell(c + i * n + tail_start, tail_length));
            simsimd_f32_t cb_j = _simsimd_reduce_f32x8_haswell(_mm256_mul_ps(b_vec, c_vec));
            sum += a_i * cb_j;
        }
    }

    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_haswell(simsimd_bf16_t const *a, simsimd_bf16_t const *b,
                                                     simsimd_bf16_t const *c, simsimd_size_t n,
                                                     simsimd_distance_t *result) {
    __m256 sum_vec = _mm256_setzero_ps();
    for (simsimd_size_t i = 0; i != n; ++i) {
        __m256 diff_i_vec = _mm256_sub_ps(              //
            _mm256_set1_ps(simsimd_bf16_to_f32(a + i)), //
            _mm256_set1_ps(simsimd_bf16_to_f32(b + i)));
        __m256 cdiff_j_vec = _mm256_setzero_ps();
        for (simsimd_size_t j = 0; j + 8 <= n; j += 8) {
            __m256 diff_j_vec = _mm256_sub_ps(                                               //
                _simsimd_bf16x8_to_f32x8_haswell(_mm_lddqu_si128((__m128i const *)(a + j))), //
                _simsimd_bf16x8_to_f32x8_haswell(_mm_lddqu_si128((__m128i const *)(b + j))));
            __m256 c_vec = _simsimd_bf16x8_to_f32x8_haswell(_mm_lddqu_si128((__m128i const *)(c + i * n + j)));
            cdiff_j_vec = _mm256_fmadd_ps(diff_j_vec, c_vec, cdiff_j_vec);
        }
        sum_vec = _mm256_fmadd_ps(diff_i_vec, cdiff_j_vec, sum_vec);
    }

    // Handle the tail of every row
    simsimd_f32_t sum = _simsimd_reduce_f32x8_haswell(sum_vec);
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    if (tail_length) {
        for (simsimd_size_t i = 0; i != n; ++i) {
            simsimd_f32_t diff_i = simsimd_bf16_to_f32(a + i) - simsimd_bf16_to_f32(b + i);
            __m256 diff_j_vec = _mm256_sub_ps( //
                _simsimd_bf16x8_to_f32x8_haswell(_simsimd_partial_load_bf16x8_haswell(a + tail_start, tail_length)),
                _simsimd_bf16x8_to_f32x8_haswell(_simsimd_partial_load_bf16x8_haswell(b + tail_start, tail_length)));
            __m256 c_vec = _simsimd_bf16x8_to_f32x8_haswell(
                _simsimd_partial_load_bf16x8_haswell(c + i * n + tail_start, tail_length));
            simsimd_f32_t cdiff_j = _simsimd_reduce_f32x8_haswell(_mm256_mul_ps(diff_j_vec, c_vec));
            sum += diff_i * cdiff_j;
        }
    }

    *result = _simsimd_sqrt_f32_haswell(sum);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_HASWELL

#if SIMSIMD_TARGET_SKYLAKE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_f32_skylake_under16unrolled(simsimd_f32_t const *a, simsimd_f32_t const *b,
                                                                 simsimd_f32_t const *c, simsimd_size_t n,
                                                                 simsimd_distance_t *result) {
    // The goal of this optimization is to avoid horizontal accumulation of the cb_j sums
    // until the very end of the computation.
    __mmask16 const mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, n);
    __m512 const b_vec = _mm512_maskz_loadu_ps(mask, b);

    __m512 cb_j1 = _mm512_setzero_ps();
    __m512 cb_j2 = _mm512_setzero_ps();
    __m512 cb_j3 = _mm512_setzero_ps();
    __m512 cb_j4 = _mm512_setzero_ps();

    // Unroll the loop to process 4x ZMM registers at a time.
    simsimd_size_t i = 0;
    for (; i + 4 <= n; i += 4) {
        cb_j1 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 0)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 0])), cb_j1);
        cb_j2 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 1)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 1])), cb_j2);
        cb_j3 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 2)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 2])), cb_j3);
        cb_j4 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 3)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 3])), cb_j4);
    }

    if (i + 0 < n)
        cb_j1 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 0)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 0])), cb_j1);
    if (i + 1 < n)
        cb_j2 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 1)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 1])), cb_j2);
    if (i + 2 < n)
        cb_j3 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 2)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 2])), cb_j3);
    if (i + 3 < n)
        cb_j4 = _mm512_fmadd_ps(_mm512_maskz_loadu_ps(mask, c + n * (i + 3)),
                                _mm512_mul_ps(b_vec, _mm512_set1_ps(a[i + 3])), cb_j4);

    // Combine cb_j sums
    __m512 sum_vec = _mm512_add_ps(  //
        _mm512_add_ps(cb_j1, cb_j2), //
        _mm512_add_ps(cb_j3, cb_j4));
    *result = _mm512_reduce_add_ps(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_bilinear_f32_skylake(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_f32_t const *c,
                                                 simsimd_size_t n, simsimd_distance_t *result) {

    // On modern x86 CPUs we have enough register space to load fairly large matrices with up to 16 cells
    // per row and 16 rows at a time, still keeping enough register space for temporaries.
    if (n <= 16) {
        simsimd_bilinear_f32_skylake_under16unrolled(a, b, c, n, result);
        return;
    }

    // Default case for arbitrary size `n`
    simsimd_size_t const tail_length = n % 16;
    simsimd_size_t const tail_start = n - tail_length;
    __m512 sum_vec = _mm512_setzero_ps();
    __mmask16 const tail_mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, tail_length);

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512 a_vec = _mm512_set1_ps(a[i]);
        __m512 cb_j_vec = _mm512_setzero_ps();
        __m512 b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_f32_skylake_cycle:
        if (j + 16 <= n) {
            b_vec = _mm512_loadu_ps(b + j);
            c_vec = _mm512_loadu_ps(c + i * n + j);
        }
        else {
            b_vec = _mm512_maskz_loadu_ps(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_ps(tail_mask, c + i * n + tail_start);
        }
        cb_j_vec = _mm512_fmadd_ps(b_vec, c_vec, cb_j_vec);
        j += 16;
        if (j < n) goto simsimd_bilinear_f32_skylake_cycle;
        sum_vec = _mm512_fmadd_ps(a_vec, cb_j_vec, sum_vec);
    }

    *result = _mm512_reduce_add_ps(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_f32_skylake(simsimd_f32_t const *a, simsimd_f32_t const *b,
                                                    simsimd_f32_t const *c, simsimd_size_t n,
                                                    simsimd_distance_t *result) {
    simsimd_size_t const tail_length = n % 16;
    simsimd_size_t const tail_start = n - tail_length;
    __m512 sum_vec = _mm512_setzero_ps();
    __mmask16 const tail_mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, tail_length);

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512 diff_i_vec = _mm512_set1_ps(a[i] - b[i]);
        __m512 cdiff_j_vec = _mm512_setzero_ps(), cdiff_j_bot_vec = _mm512_setzero_ps();
        __m512 a_j_vec, b_j_vec, diff_j_vec, c_vec;
        simsimd_size_t j = 0;

        // The nested loop is cleaner to implement with a `goto` in this case:
    simsimd_bilinear_f32_skylake_cycle:
        if (j + 16 <= n) {
            a_j_vec = _mm512_loadu_ps(a + j);
            b_j_vec = _mm512_loadu_ps(b + j);
            c_vec = _mm512_loadu_ps(c + i * n + j);
        }
        else {
            a_j_vec = _mm512_maskz_loadu_ps(tail_mask, a + tail_start);
            b_j_vec = _mm512_maskz_loadu_ps(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_ps(tail_mask, c + i * n + tail_start);
        }
        diff_j_vec = _mm512_sub_ps(a_j_vec, b_j_vec);
        cdiff_j_vec = _mm512_fmadd_ps(diff_j_vec, c_vec, cdiff_j_vec);
        j += 16;
        if (j < n) goto simsimd_bilinear_f32_skylake_cycle;
        sum_vec = _mm512_fmadd_ps(diff_i_vec, cdiff_j_vec, sum_vec);
    }

    *result = _simsimd_sqrt_f64_haswell(_mm512_reduce_add_ps(sum_vec));
}

SIMSIMD_PUBLIC void simsimd_bilinear_f32c_skylake(simsimd_f32c_t const *a, simsimd_f32c_t const *b,
                                                  simsimd_f32c_t const *c, simsimd_size_t n,
                                                  simsimd_distance_t *results) {

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set1_epi64(0x8000000000000000);

    // Default case for arbitrary size `n`
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask16 const tail_mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, tail_length * 2);
    simsimd_f32_t sum_real = 0;
    simsimd_f32_t sum_imag = 0;

    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32_t const a_i_real = a[i].real;
        simsimd_f32_t const a_i_imag = a[i].imag;
        __m512 cb_j_real_vec = _mm512_setzero_ps();
        __m512 cb_j_imag_vec = _mm512_setzero_ps();
        __m512 b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_f32c_skylake_cycle:
        if (j + 8 <= n) {
            b_vec = _mm512_loadu_ps((simsimd_f32_t const *)(b + j));
            c_vec = _mm512_loadu_ps((simsimd_f32_t const *)(c + i * n + j));
        }
        else {
            b_vec = _mm512_maskz_loadu_ps(tail_mask, (simsimd_f32_t const *)(b + tail_start));
            c_vec = _mm512_maskz_loadu_ps(tail_mask, (simsimd_f32_t const *)(c + i * n + tail_start));
        }
        // The real part of the product: b.real * c.real - b.imag * c.imag.
        // The subtraction will be performed later with a sign flip.
        cb_j_real_vec = _mm512_fmadd_ps(c_vec, b_vec, cb_j_real_vec);
        // The imaginary part of the product: b.real * c.imag + b.imag * c.real.
        // Swap the imaginary and real parts of `c` before multiplication:
        c_vec = _mm512_permute_ps(c_vec, 0xB1); //? Swap adjacent entries within each pair
        cb_j_imag_vec = _mm512_fmadd_ps(c_vec, b_vec, cb_j_imag_vec);
        j += 8;
        if (j < n) goto simsimd_bilinear_f32c_skylake_cycle;
        // Flip the sign bit in every second scalar before accumulation:
        cb_j_real_vec = _mm512_castsi512_ps(_mm512_xor_si512(_mm512_castps_si512(cb_j_real_vec), sign_flip_vec));
        // Horizontal sums are the expensive part of the computation:
        simsimd_f32_t const cb_j_real = _mm512_reduce_add_ps(cb_j_real_vec);
        simsimd_f32_t const cb_j_imag = _mm512_reduce_add_ps(cb_j_imag_vec);
        sum_real += a_i_real * cb_j_real - a_i_imag * cb_j_imag;
        sum_imag += a_i_real * cb_j_imag + a_i_imag * cb_j_real;
    }

    // Reduce horizontal sums:
    results[0] = sum_real;
    results[1] = sum_imag;
}

SIMSIMD_PUBLIC void simsimd_bilinear_f64_skylake_under8unrolled(simsimd_f64_t const *a, simsimd_f64_t const *b,
                                                                simsimd_f64_t const *c, simsimd_size_t n,
                                                                simsimd_distance_t *result) {

    // The goal of this optimization is to avoid horizontal accumulation of the cb_j sums
    // until the very end of the computation.
    __mmask8 const row_mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, n);
    __m512d const b_vec = _mm512_maskz_loadu_pd(row_mask, b);

    __m512d cb_j1 = _mm512_setzero_pd();
    __m512d cb_j2 = _mm512_setzero_pd();
    __m512d cb_j3 = _mm512_setzero_pd();
    __m512d cb_j4 = _mm512_setzero_pd();

    // clang-format off
    if (n > 0) cb_j1 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 0), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[0])), cb_j1);
    if (n > 1) cb_j2 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 1), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[1])), cb_j2);
    if (n > 2) cb_j3 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 2), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[2])), cb_j3);
    if (n > 3) cb_j4 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 3), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[3])), cb_j4);

    if (n > 4) cb_j1 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 4), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[4])), cb_j1);
    if (n > 5) cb_j2 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 5), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[5])), cb_j2);
    if (n > 6) cb_j3 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 6), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[6])), cb_j3);
    if (n > 7) cb_j4 = _mm512_fmadd_pd(_mm512_maskz_loadu_pd(row_mask, c + n * 7), _mm512_mul_pd(b_vec, _mm512_set1_pd(a[7])), cb_j4);
    // clang-format on

    // Combine cb_j sums
    __m512d sum_vec = _mm512_add_pd( //
        _mm512_add_pd(cb_j1, cb_j2), //
        _mm512_add_pd(cb_j3, cb_j4));
    *result = _mm512_reduce_add_pd(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_bilinear_f64_skylake(simsimd_f64_t const *a, simsimd_f64_t const *b, simsimd_f64_t const *c,
                                                 simsimd_size_t n, simsimd_distance_t *result) {

    // On modern x86 CPUs we have enough register space to load fairly large matrices with up to 16 cells
    // per row and 8 rows at a time, still keeping enough register space for temporaries.
    if (n <= 8) {
        simsimd_bilinear_f64_skylake_under8unrolled(a, b, c, n, result);
        return;
    }

    // Default case for arbitrary size `n`
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    __m512d sum_vec = _mm512_setzero_pd();
    __mmask8 const tail_mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, tail_length);

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512d a_vec = _mm512_set1_pd(a[i]);
        __m512d cb_j_vec = _mm512_setzero_pd();
        __m512d b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_f64_skylake_cycle:
        if (j + 8 <= n) {
            b_vec = _mm512_loadu_pd(b + j);
            c_vec = _mm512_loadu_pd(c + i * n + j);
        }
        else {
            b_vec = _mm512_maskz_loadu_pd(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_pd(tail_mask, c + i * n + tail_start);
        }
        cb_j_vec = _mm512_fmadd_pd(b_vec, c_vec, cb_j_vec);
        j += 8;
        if (j < n) goto simsimd_bilinear_f64_skylake_cycle;
        sum_vec = _mm512_fmadd_pd(a_vec, cb_j_vec, sum_vec);
    }

    *result = _mm512_reduce_add_pd(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_f64_skylake(simsimd_f64_t const *a, simsimd_f64_t const *b,
                                                    simsimd_f64_t const *c, simsimd_size_t n,
                                                    simsimd_distance_t *result) {
    simsimd_size_t const tail_length = n % 8;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask8 const tail_mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, tail_length);
    __m512d sum_vec = _mm512_setzero_pd();

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512d diff_i_vec = _mm512_set1_pd(a[i] - b[i]);
        __m512d cdiff_j_vec = _mm512_setzero_pd();
        __m512d a_j_vec, b_j_vec, diff_j_vec, c_vec;
        simsimd_size_t j = 0;

        // The nested loop is cleaner to implement with a `goto` in this case:
    simsimd_bilinear_f64_skylake_cycle:
        if (j + 8 <= n) {
            a_j_vec = _mm512_loadu_pd(a + j);
            b_j_vec = _mm512_loadu_pd(b + j);
            c_vec = _mm512_loadu_pd(c + i * n + j);
        }
        else {
            a_j_vec = _mm512_maskz_loadu_pd(tail_mask, a + tail_start);
            b_j_vec = _mm512_maskz_loadu_pd(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_pd(tail_mask, c + i * n + tail_start);
        }
        diff_j_vec = _mm512_sub_pd(a_j_vec, b_j_vec);
        cdiff_j_vec = _mm512_fmadd_pd(diff_j_vec, c_vec, cdiff_j_vec);
        j += 8;
        if (j < n) goto simsimd_bilinear_f64_skylake_cycle;
        sum_vec = _mm512_fmadd_pd(diff_i_vec, cdiff_j_vec, sum_vec);
    }

    *result = _simsimd_sqrt_f64_haswell(_mm512_reduce_add_pd(sum_vec));
}

SIMSIMD_PUBLIC void simsimd_bilinear_f64c_skylake(simsimd_f64c_t const *a, simsimd_f64c_t const *b,
                                                  simsimd_f64c_t const *c, simsimd_size_t n,
                                                  simsimd_distance_t *results) {

    // We take into account, that FMS is the same as FMA with a negative multiplier.
    // To multiply a floating-point value by -1, we can use the `XOR` instruction to flip the sign bit.
    // This way we can avoid the shuffling and the need for separate real and imaginary parts.
    // For the imaginary part of the product, we would need to swap the real and imaginary parts of
    // one of the vectors.
    __m512i const sign_flip_vec = _mm512_set_epi64(                                     //
        0x8000000000000000, 0x0000000000000000, 0x8000000000000000, 0x0000000000000000, //
        0x8000000000000000, 0x0000000000000000, 0x8000000000000000, 0x0000000000000000  //
    );

    // Default case for arbitrary size `n`
    simsimd_size_t const tail_length = n % 4;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask8 const tail_mask = (__mmask8)_bzhi_u32(0xFFFFFFFF, tail_length * 2);
    simsimd_f64_t sum_real = 0;
    simsimd_f64_t sum_imag = 0;

    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f64_t const a_i_real = a[i].real;
        simsimd_f64_t const a_i_imag = a[i].imag;
        __m512d cb_j_real_vec = _mm512_setzero_pd();
        __m512d cb_j_imag_vec = _mm512_setzero_pd();
        __m512d b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_f64c_skylake_cycle:
        if (j + 4 <= n) {
            b_vec = _mm512_loadu_pd((simsimd_f64_t const *)(b + j));
            c_vec = _mm512_loadu_pd((simsimd_f64_t const *)(c + i * n + j));
        }
        else {
            b_vec = _mm512_maskz_loadu_pd(tail_mask, (simsimd_f64_t const *)(b + tail_start));
            c_vec = _mm512_maskz_loadu_pd(tail_mask, (simsimd_f64_t const *)(c + i * n + tail_start));
        }
        // The real part of the product: b.real * c.real - b.imag * c.imag.
        // The subtraction will be performed later with a sign flip.
        cb_j_real_vec = _mm512_fmadd_pd(c_vec, b_vec, cb_j_real_vec);
        // The imaginary part of the product: b.real * c.imag + b.imag * c.real.
        // Swap the imaginary and real parts of `c` before multiplication:
        c_vec = _mm512_permute_pd(c_vec, 0x55); //? Same as 0b01010101.
        cb_j_imag_vec = _mm512_fmadd_pd(c_vec, b_vec, cb_j_imag_vec);
        j += 4;
        if (j < n) goto simsimd_bilinear_f64c_skylake_cycle;
        // Flip the sign bit in every second scalar before accumulation:
        cb_j_real_vec = _mm512_castsi512_pd(_mm512_xor_si512(_mm512_castpd_si512(cb_j_real_vec), sign_flip_vec));
        // Horizontal sums are the expensive part of the computation:
        simsimd_f64_t const cb_j_real = _mm512_reduce_add_pd(cb_j_real_vec);
        simsimd_f64_t const cb_j_imag = _mm512_reduce_add_pd(cb_j_imag_vec);
        sum_real += a_i_real * cb_j_real - a_i_imag * cb_j_imag;
        sum_imag += a_i_real * cb_j_imag + a_i_imag * cb_j_real;
    }

    // Reduce horizontal sums:
    results[0] = sum_real;
    results[1] = sum_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SKYLAKE

#if SIMSIMD_TARGET_GENOA
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512bw", "avx512bf16")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512bw,avx512bf16"))), \
                             apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_bf16_genoa(simsimd_bf16_t const *a, simsimd_bf16_t const *b,
                                                simsimd_bf16_t const *c, simsimd_size_t n, simsimd_distance_t *result) {
    simsimd_size_t const tail_length = n % 32;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask32 const tail_mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, tail_length);
    __m512 sum_vec = _mm512_setzero_ps();

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512 a_vec = _mm512_set1_ps(simsimd_bf16_to_f32(a + i));
        __m512 cb_j_vec = _mm512_setzero_ps();
        __m512i b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_bf16_genoa_cycle:
        if (j + 32 <= n) {
            b_vec = _mm512_loadu_epi16(b + j);
            c_vec = _mm512_loadu_epi16(c + i * n + j);
        }
        else {
            b_vec = _mm512_maskz_loadu_epi16(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_epi16(tail_mask, c + i * n + tail_start);
        }
        cb_j_vec = _mm512_dpbf16_ps(cb_j_vec, (__m512bh)(b_vec), (__m512bh)(c_vec));
        j += 32;
        if (j < n) goto simsimd_bilinear_bf16_genoa_cycle;
        sum_vec = _mm512_fmadd_ps(a_vec, cb_j_vec, sum_vec);
    }

    *result = _mm512_reduce_add_ps(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_bf16_genoa(simsimd_bf16_t const *a, simsimd_bf16_t const *b,
                                                   simsimd_bf16_t const *c, simsimd_size_t n,
                                                   simsimd_distance_t *result) {
    simsimd_size_t const tail_length = n % 32;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask32 const tail_mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, tail_length);
    __m512 sum_vec = _mm512_setzero_ps();

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512 diff_i_vec = _mm512_set1_ps(simsimd_bf16_to_f32(a + i) - simsimd_bf16_to_f32(b + i));
        __m512 cdiff_j_vec = _mm512_setzero_ps();
        __m512i a_j_vec, b_j_vec, diff_j_vec, c_vec;
        simsimd_size_t j = 0;

        // The nested loop is cleaner to implement with a `goto` in this case:
    simsimd_mahalanobis_bf16_genoa_cycle:
        if (j + 32 <= n) {
            a_j_vec = _mm512_loadu_epi16(a + j);
            b_j_vec = _mm512_loadu_epi16(b + j);
            c_vec = _mm512_loadu_epi16(c + i * n + j);
        }
        else {
            a_j_vec = _mm512_maskz_loadu_epi16(tail_mask, a + tail_start);
            b_j_vec = _mm512_maskz_loadu_epi16(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_epi16(tail_mask, c + i * n + tail_start);
        }
        diff_j_vec = _simsimd_substract_bf16x32_genoa(a_j_vec, b_j_vec);
        cdiff_j_vec = _mm512_dpbf16_ps(cdiff_j_vec, (__m512bh)(diff_j_vec), (__m512bh)(c_vec));
        j += 32;
        if (j < n) goto simsimd_mahalanobis_bf16_genoa_cycle;
        sum_vec = _mm512_fmadd_ps(diff_i_vec, cdiff_j_vec, sum_vec);
    }

    *result = _simsimd_sqrt_f32_haswell(_mm512_reduce_add_ps(sum_vec));
}

SIMSIMD_PUBLIC void simsimd_bilinear_bf16c_genoa(simsimd_bf16c_t const *a, simsimd_bf16c_t const *b,
                                                 simsimd_bf16c_t const *c, simsimd_size_t n,
                                                 simsimd_distance_t *results) {

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

    // Default case for arbitrary size `n`
    simsimd_size_t const tail_length = n % 16;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask32 const tail_mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, tail_length * 2);
    simsimd_f64_t sum_real = 0;
    simsimd_f64_t sum_imag = 0;

    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32_t const a_i_real = a[i].real;
        simsimd_f32_t const a_i_imag = a[i].imag;
        __m512 cb_j_real_vec = _mm512_setzero_ps();
        __m512 cb_j_imag_vec = _mm512_setzero_ps();
        __m512i b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_bf16c_skylake_cycle:
        if (j + 16 <= n) {
            b_vec = _mm512_loadu_epi16((simsimd_i16_t const *)(b + j));
            c_vec = _mm512_loadu_epi16((simsimd_i16_t const *)(c + i * n + j));
        }
        else {
            b_vec = _mm512_maskz_loadu_epi16(tail_mask, (simsimd_i16_t const *)(b + tail_start));
            c_vec = _mm512_maskz_loadu_epi16(tail_mask, (simsimd_i16_t const *)(c + i * n + tail_start));
        }
        cb_j_real_vec = _mm512_dpbf16_ps(                       //
            cb_j_real_vec,                                      //
            (__m512bh)(_mm512_xor_si512(c_vec, sign_flip_vec)), //
            (__m512bh)b_vec);
        cb_j_imag_vec = _mm512_dpbf16_ps(                              //
            cb_j_imag_vec,                                             //
            (__m512bh)(_mm512_shuffle_epi8(c_vec, swap_adjacent_vec)), //
            (__m512bh)b_vec);
        j += 16;
        if (j < n) goto simsimd_bilinear_bf16c_skylake_cycle;
        // Horizontal sums are the expensive part of the computation:
        simsimd_f64_t const cb_j_real = _simsimd_reduce_f32x16_skylake(cb_j_real_vec);
        simsimd_f64_t const cb_j_imag = _simsimd_reduce_f32x16_skylake(cb_j_imag_vec);
        sum_real += a_i_real * cb_j_real - a_i_imag * cb_j_imag;
        sum_imag += a_i_real * cb_j_imag + a_i_imag * cb_j_real;
    }

    // Reduce horizontal sums:
    results[0] = sum_real;
    results[1] = sum_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_GENOA

#if SIMSIMD_TARGET_SAPPHIRE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512bw", "avx512fp16")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512bw,avx512fp16"))), \
                             apply_to = function)

SIMSIMD_PUBLIC void simsimd_bilinear_f16_sapphire_under32unrolled(simsimd_f16_t const *a, simsimd_f16_t const *b,
                                                                  simsimd_f16_t const *c, simsimd_size_t const n,
                                                                  simsimd_distance_t *result) {
    // The goal of this optimization is to avoid horizontal accumulation of the cb_j sums
    // until the very end of the computation.
    __mmask32 const mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, n);
    __m512h const b_vec = _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, b));

    // Independently accumulate the partial sums into separate variables to avoid data-dependencies.
    __m512h cb_j1 = _mm512_setzero_ph();
    __m512h cb_j2 = _mm512_setzero_ph();
    __m512h cb_j3 = _mm512_setzero_ph();
    __m512h cb_j4 = _mm512_setzero_ph();

    // Unroll the loop to process 4x ZMM registers at a time.
    simsimd_size_t i = 0;
    for (; i + 4 <= n; i += 4) {
        // If the code is compiled without native support for `_Float16`, we need a workaround
        // to avoid implicit casts from out `simsimd_f16_t` to `_Float16`.
        cb_j1 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 0))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 0]))), cb_j1);
        cb_j2 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 1))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 1]))), cb_j2);
        cb_j3 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 2))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 2]))), cb_j3);
        cb_j4 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 3))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 3]))), cb_j4);
    }

    // Handle the tail of the loop:
    if (i + 0 < n)
        cb_j1 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 0))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 0]))), cb_j1);
    if (i + 1 < n)
        cb_j2 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 1))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 1]))), cb_j2);
    if (i + 2 < n)
        cb_j3 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 2))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 2]))), cb_j3);
    if (i + 3 < n)
        cb_j4 = _mm512_fmadd_ph(
            _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, c + n * (i + 3))),
            _mm512_mul_ph(b_vec, _mm512_castsi512_ph(_mm512_set1_epi16(((simsimd_i16_t const *)a)[i + 3]))), cb_j4);

    // Combine cb_j sums
    __m512h sum_vec = _mm512_add_ph( //
        _mm512_add_ph(cb_j1, cb_j2), //
        _mm512_add_ph(cb_j3, cb_j4));
    *result = _mm512_reduce_add_ph(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_bilinear_f16_sapphire(simsimd_f16_t const *a, simsimd_f16_t const *b,
                                                  simsimd_f16_t const *c, simsimd_size_t n,
                                                  simsimd_distance_t *result) {

    // On modern x86 CPUs we have enough register space to load fairly large matrices with up to 32 cells
    // per row and 32 rows at a time, still keeping enough register space for temporaries.
    if (n <= 32) {
        simsimd_bilinear_f16_sapphire_under32unrolled(a, b, c, n, result);
        return;
    }

    simsimd_size_t const tail_length = n % 32;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask32 const tail_mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, tail_length);
    __m512h sum_vec = _mm512_setzero_ph();

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512h a_vec = _mm512_castsi512_ph(_mm512_set1_epi16(*(short const *)(a + i)));
        __m512h cb_j_vec = _mm512_setzero_ph();
        __m512i b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_f16_sapphire_cycle:
        if (j + 32 <= n) {
            b_vec = _mm512_loadu_epi16(b + j);
            c_vec = _mm512_loadu_epi16(c + i * n + j);
        }
        else {
            b_vec = _mm512_maskz_loadu_epi16(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_epi16(tail_mask, c + i * n + tail_start);
        }
        cb_j_vec = _mm512_fmadd_ph(_mm512_castsi512_ph(b_vec), _mm512_castsi512_ph(c_vec), cb_j_vec);
        j += 32;
        if (j < n) goto simsimd_bilinear_f16_sapphire_cycle;
        sum_vec = _mm512_fmadd_ph(a_vec, cb_j_vec, sum_vec);
    }

    *result = _mm512_reduce_add_ph(sum_vec);
}

SIMSIMD_PUBLIC void simsimd_mahalanobis_f16_sapphire(simsimd_f16_t const *a, simsimd_f16_t const *b,
                                                     simsimd_f16_t const *c, simsimd_size_t n,
                                                     simsimd_distance_t *result) {
    simsimd_size_t const tail_length = n % 32;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask32 const tail_mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, tail_length);
    __m512h sum_vec = _mm512_setzero_ph();

    for (simsimd_size_t i = 0; i != n; ++i) {
        __m512h a_i_vec = _mm512_castsi512_ph(_mm512_set1_epi16(*(short const *)(a + i)));
        __m512h b_i_vec = _mm512_castsi512_ph(_mm512_set1_epi16(*(short const *)(b + i)));
        __m512h diff_i_vec = _mm512_sub_ph(a_i_vec, b_i_vec);
        __m512h cdiff_j_vec = _mm512_setzero_ph();
        __m512h diff_j_vec;
        __m512i a_j_vec, b_j_vec, c_vec;
        simsimd_size_t j = 0;

        // The nested loop is cleaner to implement with a `goto` in this case:
    simsimd_mahalanobis_f16_sapphire_cycle:
        if (j + 32 <= n) {
            a_j_vec = _mm512_loadu_epi16(a + j);
            b_j_vec = _mm512_loadu_epi16(b + j);
            c_vec = _mm512_loadu_epi16(c + i * n + j);
        }
        else {
            a_j_vec = _mm512_maskz_loadu_epi16(tail_mask, a + tail_start);
            b_j_vec = _mm512_maskz_loadu_epi16(tail_mask, b + tail_start);
            c_vec = _mm512_maskz_loadu_epi16(tail_mask, c + i * n + tail_start);
        }
        diff_j_vec = _mm512_sub_ph(_mm512_castsi512_ph(a_j_vec), _mm512_castsi512_ph(b_j_vec));
        cdiff_j_vec = _mm512_fmadd_ph(diff_j_vec, _mm512_castsi512_ph(c_vec), cdiff_j_vec);
        j += 32;
        if (j < n) goto simsimd_mahalanobis_f16_sapphire_cycle;
        sum_vec = _mm512_fmadd_ph(diff_i_vec, cdiff_j_vec, sum_vec);
    }

    *result = _simsimd_sqrt_f32_haswell(_mm512_reduce_add_ph(sum_vec));
}

SIMSIMD_PUBLIC void simsimd_bilinear_f16c_sapphire(simsimd_f16c_t const *a, simsimd_f16c_t const *b,
                                                   simsimd_f16c_t const *c, simsimd_size_t n,
                                                   simsimd_distance_t *results) {

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

    // Default case for arbitrary size `n`
    simsimd_size_t const tail_length = n % 16;
    simsimd_size_t const tail_start = n - tail_length;
    __mmask32 const tail_mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, tail_length * 2);
    simsimd_f32_t sum_real = 0;
    simsimd_f32_t sum_imag = 0;

    for (simsimd_size_t i = 0; i != n; ++i) {
        simsimd_f32_t const a_i_real = a[i].real;
        simsimd_f32_t const a_i_imag = a[i].imag;
        __m512h cb_j_real_vec = _mm512_setzero_ph();
        __m512h cb_j_imag_vec = _mm512_setzero_ph();
        __m512i b_vec, c_vec;
        simsimd_size_t j = 0;

    simsimd_bilinear_f16c_skylake_cycle:
        if (j + 16 <= n) {
            b_vec = _mm512_loadu_epi16((simsimd_i16_t const *)(b + j));
            c_vec = _mm512_loadu_epi16((simsimd_i16_t const *)(c + i * n + j));
        }
        else {
            b_vec = _mm512_maskz_loadu_epi16(tail_mask, (simsimd_i16_t const *)(b + tail_start));
            c_vec = _mm512_maskz_loadu_epi16(tail_mask, (simsimd_i16_t const *)(c + i * n + tail_start));
        }
        cb_j_real_vec = _mm512_fmadd_ph(                                 //
            _mm512_castsi512_ph(_mm512_xor_si512(c_vec, sign_flip_vec)), //
            _mm512_castsi512_ph(b_vec), cb_j_real_vec);
        cb_j_imag_vec = _mm512_fmadd_ph(                                        //
            _mm512_castsi512_ph(_mm512_shuffle_epi8(c_vec, swap_adjacent_vec)), //
            _mm512_castsi512_ph(b_vec), cb_j_imag_vec);
        j += 16;
        if (j < n) goto simsimd_bilinear_f16c_skylake_cycle;
        // Horizontal sums are the expensive part of the computation:
        simsimd_f32_t const cb_j_real = _mm512_reduce_add_ph(cb_j_real_vec);
        simsimd_f32_t const cb_j_imag = _mm512_reduce_add_ph(cb_j_imag_vec);
        sum_real += a_i_real * cb_j_real - a_i_imag * cb_j_imag;
        sum_imag += a_i_real * cb_j_imag + a_i_imag * cb_j_real;
    }

    // Reduce horizontal sums:
    results[0] = sum_real;
    results[1] = sum_imag;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SAPPHIRE
#endif // _SIMSIMD_TARGET_X86

#ifdef __cplusplus
}
#endif

#endif
