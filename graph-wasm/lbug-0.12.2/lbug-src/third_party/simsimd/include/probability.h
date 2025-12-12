/**
 *  @file       probability.h
 *  @brief      SIMD-accelerated Similarity Measures for Probability Distributions.
 *  @author     Ash Vardanian
 *  @date       October 20, 2023
 *
 *  Contains:
 *  - Kullback-Leibler divergence
 *  - Jensen–Shannon divergence
 *
 *  For datatypes:
 *  - 32-bit floating point numbers
 *  - 16-bit floating point numbers
 *  - 16-bit brain-floating point numbers
 *
 *  For hardware architectures:
 *  - Arm: NEON
 *  - x86: Haswell, Skylake, Sapphire
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 */
#ifndef SIMSIMD_PROBABILITY_H
#define SIMSIMD_PROBABILITY_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

// clang-format off

/*  Serial backends for all numeric types.
 *  By default they use 32-bit arithmetic, unless the arguments themselves contain 64-bit floats.
 *  For double-precision computation check out the "*_accurate" variants of those "*_serial" functions.
 */
SIMSIMD_PUBLIC void simsimd_kl_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);

/*  Double-precision serial backends for all numeric types.
 *  For single-precision computation check out the "*_serial" counterparts of those "*_accurate" functions.
 */
SIMSIMD_PUBLIC void simsimd_kl_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);

/*  SIMD-powered backends for Arm NEON, mostly using 32-bit arithmetic over 128-bit words.
 *  By far the most portable backend, covering most Arm v8 devices, over a billion phones, and almost all
 *  server CPUs produced before 2023.
 */
SIMSIMD_PUBLIC void simsimd_kl_f32_neon(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f32_neon(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_f16_neon(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f16_neon(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);

/*  SIMD-powered backends for AVX2 CPUs of Haswell generation and newer, using 32-bit arithmetic over 256-bit words.
 *  First demonstrated in 2011, at least one Haswell-based processor was still being sold in 2022 — the Pentium G3420.
 *  Practically all modern x86 CPUs support AVX2, FMA, and F16C, making it a perfect baseline for SIMD algorithms.
 *  On other hand, there is no need to implement AVX2 versions of `f32` and `f64` functions, as those are
 *  properly vectorized by recent compilers.
 */
SIMSIMD_PUBLIC void simsimd_kl_f16_haswell(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f16_haswell(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);

/*  SIMD-powered backends for various generations of AVX512 CPUs.
 *  Skylake is handy, as it supports masked loads and other operations, avoiding the need for the tail loop.
 *  Ice Lake added VNNI, VPOPCNTDQ, IFMA, VBMI, VAES, GFNI, VBMI2, BITALG, VPCLMULQDQ, and other extensions for integral operations.
 *  Sapphire Rapids added tiled matrix operations, but we are most interested in the new mixed-precision FMA instructions.
 */
SIMSIMD_PUBLIC void simsimd_kl_f32_skylake(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f32_skylake(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_kl_f16_sapphire(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
SIMSIMD_PUBLIC void simsimd_js_f16_sapphire(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_distance_t* divergence);
// clang-format on

#define SIMSIMD_MAKE_KL(name, input_type, accumulator_type, load_and_convert, epsilon)                        \
    SIMSIMD_PUBLIC void simsimd_kl_##input_type##_##name(simsimd_##input_type##_t const *a,                   \
                                                         simsimd_##input_type##_t const *b, simsimd_size_t n, \
                                                         simsimd_distance_t *result) {                        \
        simsimd_##accumulator_type##_t d = 0;                                                                 \
        for (simsimd_size_t i = 0; i != n; ++i) {                                                             \
            simsimd_##accumulator_type##_t ai = load_and_convert(a + i);                                      \
            simsimd_##accumulator_type##_t bi = load_and_convert(b + i);                                      \
            d += ai * SIMSIMD_LOG((ai + epsilon) / (bi + epsilon));                                           \
        }                                                                                                     \
        *result = (simsimd_distance_t)d;                                                                      \
    }

#define SIMSIMD_MAKE_JS(name, input_type, accumulator_type, load_and_convert, epsilon)                        \
    SIMSIMD_PUBLIC void simsimd_js_##input_type##_##name(simsimd_##input_type##_t const *a,                   \
                                                         simsimd_##input_type##_t const *b, simsimd_size_t n, \
                                                         simsimd_distance_t *result) {                        \
        simsimd_##accumulator_type##_t d = 0;                                                                 \
        for (simsimd_size_t i = 0; i != n; ++i) {                                                             \
            simsimd_##accumulator_type##_t ai = load_and_convert(a + i);                                      \
            simsimd_##accumulator_type##_t bi = load_and_convert(b + i);                                      \
            simsimd_##accumulator_type##_t mi = (ai + bi) / 2;                                                \
            d += ai * SIMSIMD_LOG((ai + epsilon) / (mi + epsilon));                                           \
            d += bi * SIMSIMD_LOG((bi + epsilon) / (mi + epsilon));                                           \
        }                                                                                                     \
        *result = SIMSIMD_SQRT(((simsimd_distance_t)d / 2));                                                  \
    }

SIMSIMD_MAKE_KL(serial, f64, f64, SIMSIMD_DEREFERENCE, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_f64_serial
SIMSIMD_MAKE_JS(serial, f64, f64, SIMSIMD_DEREFERENCE, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_f64_serial

SIMSIMD_MAKE_KL(serial, f32, f32, SIMSIMD_DEREFERENCE, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_f32_serial
SIMSIMD_MAKE_JS(serial, f32, f32, SIMSIMD_DEREFERENCE, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_f32_serial

SIMSIMD_MAKE_KL(serial, f16, f32, SIMSIMD_F16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_f16_serial
SIMSIMD_MAKE_JS(serial, f16, f32, SIMSIMD_F16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_f16_serial

SIMSIMD_MAKE_KL(serial, bf16, f32, SIMSIMD_BF16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_bf16_serial
SIMSIMD_MAKE_JS(serial, bf16, f32, SIMSIMD_BF16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_bf16_serial

SIMSIMD_MAKE_KL(accurate, f32, f64, SIMSIMD_DEREFERENCE, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_f32_accurate
SIMSIMD_MAKE_JS(accurate, f32, f64, SIMSIMD_DEREFERENCE, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_f32_accurate

SIMSIMD_MAKE_KL(accurate, f16, f64, SIMSIMD_F16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_f16_accurate
SIMSIMD_MAKE_JS(accurate, f16, f64, SIMSIMD_F16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_f16_accurate

SIMSIMD_MAKE_KL(accurate, bf16, f64, SIMSIMD_BF16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_kl_bf16_accurate
SIMSIMD_MAKE_JS(accurate, bf16, f64, SIMSIMD_BF16_TO_F32, SIMSIMD_F32_DIVISION_EPSILON) // simsimd_js_bf16_accurate

#if _SIMSIMD_TARGET_ARM
#if SIMSIMD_TARGET_NEON
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd"))), apply_to = function)

SIMSIMD_PUBLIC float32x4_t _simsimd_log2_f32_neon(float32x4_t x) {
    // Extracting the exponent
    int32x4_t i = vreinterpretq_s32_f32(x);
    int32x4_t e = vsubq_s32(vshrq_n_s32(vandq_s32(i, vdupq_n_s32(0x7F800000)), 23), vdupq_n_s32(127));
    float32x4_t e_float = vcvtq_f32_s32(e);

    // Extracting the mantissa
    float32x4_t m = vreinterpretq_f32_s32(vorrq_s32(vandq_s32(i, vdupq_n_s32(0x007FFFFF)), vdupq_n_s32(0x3F800000)));

    // Constants for polynomial
    float32x4_t one = vdupq_n_f32(1.0f);
    float32x4_t p = vdupq_n_f32(-3.4436006e-2f);

    // Compute polynomial using Horner's method
    p = vmlaq_f32(vdupq_n_f32(3.1821337e-1f), m, p);
    p = vmlaq_f32(vdupq_n_f32(-1.2315303f), m, p);
    p = vmlaq_f32(vdupq_n_f32(2.5988452f), m, p);
    p = vmlaq_f32(vdupq_n_f32(-3.3241990f), m, p);
    p = vmlaq_f32(vdupq_n_f32(3.1157899f), m, p);

    // Final computation
    float32x4_t result = vaddq_f32(vmulq_f32(p, vsubq_f32(m, one)), e_float);
    return result;
}

SIMSIMD_PUBLIC void simsimd_kl_f32_neon(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_size_t n,
                                        simsimd_distance_t *result) {
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    float32x4_t epsilon_vec = vdupq_n_f32(epsilon);
    float32x4_t sum_vec = vdupq_n_f32(0);
    float32x4_t a_vec, b_vec;

simsimd_kl_f32_neon_cycle:
    if (n < 4) {
        a_vec = _simsimd_partial_load_f32x4_neon(a, n);
        b_vec = _simsimd_partial_load_f32x4_neon(b, n);
        n = 0;
    }
    else {
        a_vec = vld1q_f32(a);
        b_vec = vld1q_f32(b);
        n -= 4, a += 4, b += 4;
    }

    float32x4_t ratio_vec = vdivq_f32(vaddq_f32(a_vec, epsilon_vec), vaddq_f32(b_vec, epsilon_vec));
    float32x4_t log_ratio_vec = _simsimd_log2_f32_neon(ratio_vec);
    float32x4_t prod_vec = vmulq_f32(a_vec, log_ratio_vec);
    sum_vec = vaddq_f32(sum_vec, prod_vec);
    if (n != 0) goto simsimd_kl_f32_neon_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = vaddvq_f32(sum_vec) * log2_normalizer;
    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_js_f32_neon(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_size_t n,
                                        simsimd_distance_t *result) {
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    float32x4_t epsilon_vec = vdupq_n_f32(epsilon);
    float32x4_t sum_vec = vdupq_n_f32(0);
    float32x4_t a_vec, b_vec;

simsimd_js_f32_neon_cycle:
    if (n < 4) {
        a_vec = _simsimd_partial_load_f32x4_neon(a, n);
        b_vec = _simsimd_partial_load_f32x4_neon(b, n);
        n = 0;
    }
    else {
        a_vec = vld1q_f32(a);
        b_vec = vld1q_f32(b);
        n -= 4, a += 4, b += 4;
    }

    float32x4_t m_vec = vmulq_f32(vaddq_f32(a_vec, b_vec), vdupq_n_f32(0.5));
    float32x4_t ratio_a_vec = vdivq_f32(vaddq_f32(a_vec, epsilon_vec), vaddq_f32(m_vec, epsilon_vec));
    float32x4_t ratio_b_vec = vdivq_f32(vaddq_f32(b_vec, epsilon_vec), vaddq_f32(m_vec, epsilon_vec));
    float32x4_t log_ratio_a_vec = _simsimd_log2_f32_neon(ratio_a_vec);
    float32x4_t log_ratio_b_vec = _simsimd_log2_f32_neon(ratio_b_vec);
    float32x4_t prod_a_vec = vmulq_f32(a_vec, log_ratio_a_vec);
    float32x4_t prod_b_vec = vmulq_f32(b_vec, log_ratio_b_vec);

    sum_vec = vaddq_f32(sum_vec, vaddq_f32(prod_a_vec, prod_b_vec));
    if (n != 0) goto simsimd_js_f32_neon_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = vaddvq_f32(sum_vec) * log2_normalizer / 2;
    *result = _simsimd_sqrt_f32_neon(sum);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON

#if SIMSIMD_TARGET_NEON_F16
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd+fp16")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd+fp16"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_kl_f16_neon(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_size_t n,
                                        simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    float32x4_t epsilon_vec = vdupq_n_f32(epsilon);
    float32x4_t a_vec, b_vec;

simsimd_kl_f16_neon_cycle:
    if (n < 4) {
        a_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(a, n));
        b_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(b, n));
        n = 0;
    }
    else {
        a_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)a));
        b_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)b));
        n -= 4, a += 4, b += 4;
    }

    float32x4_t ratio_vec = vdivq_f32(vaddq_f32(a_vec, epsilon_vec), vaddq_f32(b_vec, epsilon_vec));
    float32x4_t log_ratio_vec = _simsimd_log2_f32_neon(ratio_vec);
    float32x4_t prod_vec = vmulq_f32(a_vec, log_ratio_vec);
    sum_vec = vaddq_f32(sum_vec, prod_vec);
    if (n) goto simsimd_kl_f16_neon_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = vaddvq_f32(sum_vec) * log2_normalizer;
    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_js_f16_neon(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_size_t n,
                                        simsimd_distance_t *result) {
    float32x4_t sum_vec = vdupq_n_f32(0);
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    float32x4_t epsilon_vec = vdupq_n_f32(epsilon);
    float32x4_t a_vec, b_vec;

simsimd_js_f16_neon_cycle:
    if (n < 4) {
        a_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(a, n));
        b_vec = vcvt_f32_f16(_simsimd_partial_load_f16x4_neon(b, n));
        n = 0;
    }
    else {
        a_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)a));
        b_vec = vcvt_f32_f16(vld1_f16((simsimd_f16_for_arm_simd_t const *)b));
        n -= 4, a += 4, b += 4;
    }

    float32x4_t m_vec = vmulq_f32(vaddq_f32(a_vec, b_vec), vdupq_n_f32(0.5));
    float32x4_t ratio_a_vec = vdivq_f32(vaddq_f32(a_vec, epsilon_vec), vaddq_f32(m_vec, epsilon_vec));
    float32x4_t ratio_b_vec = vdivq_f32(vaddq_f32(b_vec, epsilon_vec), vaddq_f32(m_vec, epsilon_vec));
    float32x4_t log_ratio_a_vec = _simsimd_log2_f32_neon(ratio_a_vec);
    float32x4_t log_ratio_b_vec = _simsimd_log2_f32_neon(ratio_b_vec);
    float32x4_t prod_a_vec = vmulq_f32(a_vec, log_ratio_a_vec);
    float32x4_t prod_b_vec = vmulq_f32(b_vec, log_ratio_b_vec);
    sum_vec = vaddq_f32(sum_vec, vaddq_f32(prod_a_vec, prod_b_vec));
    if (n) goto simsimd_js_f16_neon_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = vaddvq_f32(sum_vec) * log2_normalizer / 2;
    *result = _simsimd_sqrt_f32_neon(sum);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON_F16
#endif // _SIMSIMD_TARGET_ARM

#if _SIMSIMD_TARGET_X86
#if SIMSIMD_TARGET_HASWELL
#pragma GCC push_options
#pragma GCC target("avx2", "f16c", "fma")
#pragma clang attribute push(__attribute__((target("avx2,f16c,fma"))), apply_to = function)

SIMSIMD_INTERNAL __m256 _simsimd_log2_f32_haswell(__m256 x) {
    // Extracting the exponent
    __m256i i = _mm256_castps_si256(x);
    __m256i e = _mm256_srli_epi32(_mm256_and_si256(i, _mm256_set1_epi32(0x7F800000)), 23);
    e = _mm256_sub_epi32(e, _mm256_set1_epi32(127)); // removing the bias
    __m256 e_float = _mm256_cvtepi32_ps(e);

    // Extracting the mantissa
    __m256 m = _mm256_castsi256_ps(
        _mm256_or_si256(_mm256_and_si256(i, _mm256_set1_epi32(0x007FFFFF)), _mm256_set1_epi32(0x3F800000)));

    // Constants for polynomial
    __m256 one = _mm256_set1_ps(1.0f);
    __m256 p = _mm256_set1_ps(-3.4436006e-2f);

    // Compute the polynomial using Horner's method
    p = _mm256_fmadd_ps(m, p, _mm256_set1_ps(3.1821337e-1f));
    p = _mm256_fmadd_ps(m, p, _mm256_set1_ps(-1.2315303f));
    p = _mm256_fmadd_ps(m, p, _mm256_set1_ps(2.5988452f));
    p = _mm256_fmadd_ps(m, p, _mm256_set1_ps(-3.3241990f));
    p = _mm256_fmadd_ps(m, p, _mm256_set1_ps(3.1157899f));

    // Final computation
    __m256 result = _mm256_add_ps(_mm256_mul_ps(p, _mm256_sub_ps(m, one)), e_float);
    return result;
}

SIMSIMD_PUBLIC void simsimd_kl_f16_haswell(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_size_t n,
                                           simsimd_distance_t *result) {
    __m256 sum_vec = _mm256_setzero_ps();
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    __m256 epsilon_vec = _mm256_set1_ps(epsilon);
    __m256 a_vec, b_vec;

simsimd_kl_f16_haswell_cycle:
    if (n < 8) {
        a_vec = _simsimd_partial_load_f16x8_haswell(a, n);
        b_vec = _simsimd_partial_load_f16x8_haswell(b, n);
        n = 0;
    }
    else {
        a_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)a));
        b_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)b));
        n -= 8, a += 8, b += 8;
    }
    a_vec = _mm256_add_ps(a_vec, epsilon_vec);
    b_vec = _mm256_add_ps(b_vec, epsilon_vec);
    __m256 ratio_vec = _mm256_div_ps(a_vec, b_vec);
    __m256 log_ratio_vec = _simsimd_log2_f32_haswell(ratio_vec);
    __m256 prod_vec = _mm256_mul_ps(a_vec, log_ratio_vec);
    sum_vec = _mm256_add_ps(sum_vec, prod_vec);
    if (n) goto simsimd_kl_f16_haswell_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = _simsimd_reduce_f32x8_haswell(sum_vec);
    sum *= log2_normalizer;
    *result = sum;
}

SIMSIMD_PUBLIC void simsimd_js_f16_haswell(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_size_t n,
                                           simsimd_distance_t *result) {
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    __m256 epsilon_vec = _mm256_set1_ps(epsilon);
    __m256 sum_vec = _mm256_setzero_ps();
    __m256 a_vec, b_vec;

simsimd_js_f16_haswell_cycle:
    if (n < 8) {
        a_vec = _simsimd_partial_load_f16x8_haswell(a, n);
        b_vec = _simsimd_partial_load_f16x8_haswell(b, n);
        n = 0;
    }
    else {
        a_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)a));
        b_vec = _mm256_cvtph_ps(_mm_lddqu_si128((__m128i const *)b));
        n -= 8, a += 8, b += 8;
    }
    a_vec = _mm256_add_ps(a_vec, epsilon_vec);
    b_vec = _mm256_add_ps(b_vec, epsilon_vec);
    __m256 m_vec = _mm256_mul_ps(_mm256_add_ps(a_vec, b_vec), _mm256_set1_ps(0.5f)); // M = (P + Q) / 2
    __m256 ratio_a_vec = _mm256_div_ps(a_vec, m_vec);
    __m256 ratio_b_vec = _mm256_div_ps(b_vec, m_vec);
    __m256 log_ratio_a_vec = _simsimd_log2_f32_haswell(ratio_a_vec);
    __m256 log_ratio_b_vec = _simsimd_log2_f32_haswell(ratio_b_vec);
    __m256 prod_a_vec = _mm256_mul_ps(a_vec, log_ratio_a_vec);
    __m256 prod_b_vec = _mm256_mul_ps(b_vec, log_ratio_b_vec);
    sum_vec = _mm256_add_ps(sum_vec, prod_a_vec);
    sum_vec = _mm256_add_ps(sum_vec, prod_b_vec);
    if (n) goto simsimd_js_f16_haswell_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = _simsimd_reduce_f32x8_haswell(sum_vec);
    sum *= log2_normalizer / 2;
    *result = _simsimd_sqrt_f32_haswell(sum);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_HASWELL

#if SIMSIMD_TARGET_SKYLAKE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2"))), apply_to = function)

SIMSIMD_INTERNAL __m512 _simsimd_log2_f32_skylake(__m512 x) {
    // Extract the exponent and mantissa
    __m512 one = _mm512_set1_ps(1.0f);
    __m512 e = _mm512_getexp_ps(x);
    __m512 m = _mm512_getmant_ps(x, _MM_MANT_NORM_1_2, _MM_MANT_SIGN_src);

    // Compute the polynomial using Horner's method
    __m512 p = _mm512_set1_ps(-3.4436006e-2f);
    p = _mm512_fmadd_ps(m, p, _mm512_set1_ps(3.1821337e-1f));
    p = _mm512_fmadd_ps(m, p, _mm512_set1_ps(-1.2315303f));
    p = _mm512_fmadd_ps(m, p, _mm512_set1_ps(2.5988452f));
    p = _mm512_fmadd_ps(m, p, _mm512_set1_ps(-3.3241990f));
    p = _mm512_fmadd_ps(m, p, _mm512_set1_ps(3.1157899f));

    return _mm512_add_ps(_mm512_mul_ps(p, _mm512_sub_ps(m, one)), e);
}

SIMSIMD_PUBLIC void simsimd_kl_f32_skylake(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_size_t n,
                                           simsimd_distance_t *result) {
    __m512 sum_vec = _mm512_setzero();
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    __m512 epsilon_vec = _mm512_set1_ps(epsilon);
    __m512 a_vec, b_vec;

simsimd_kl_f32_skylake_cycle:
    if (n < 16) {
        __mmask16 mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, n);
        a_vec = _mm512_add_ps(_mm512_maskz_loadu_ps(mask, a), epsilon_vec);
        b_vec = _mm512_add_ps(_mm512_maskz_loadu_ps(mask, b), epsilon_vec);
        n = 0;
    }
    else {
        a_vec = _mm512_add_ps(_mm512_loadu_ps(a), epsilon_vec);
        b_vec = _mm512_add_ps(_mm512_loadu_ps(b), epsilon_vec);
        a += 16, b += 16, n -= 16;
    }
    __m512 ratio_vec = _mm512_div_ps(a_vec, b_vec);
    __m512 log_ratio_vec = _simsimd_log2_f32_skylake(ratio_vec);
    __m512 prod_vec = _mm512_mul_ps(a_vec, log_ratio_vec);
    sum_vec = _mm512_add_ps(sum_vec, prod_vec);
    if (n) goto simsimd_kl_f32_skylake_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    *result = _mm512_reduce_add_ps(sum_vec) * log2_normalizer;
}

SIMSIMD_PUBLIC void simsimd_js_f32_skylake(simsimd_f32_t const *a, simsimd_f32_t const *b, simsimd_size_t n,
                                           simsimd_distance_t *result) {
    __m512 sum_a_vec = _mm512_setzero();
    __m512 sum_b_vec = _mm512_setzero();
    simsimd_f32_t epsilon = SIMSIMD_F32_DIVISION_EPSILON;
    __m512 epsilon_vec = _mm512_set1_ps(epsilon);
    __m512 a_vec, b_vec;

simsimd_js_f32_skylake_cycle:
    if (n < 16) {
        __mmask16 mask = (__mmask16)_bzhi_u32(0xFFFFFFFF, n);
        a_vec = _mm512_maskz_loadu_ps(mask, a);
        b_vec = _mm512_maskz_loadu_ps(mask, b);
        n = 0;
    }
    else {
        a_vec = _mm512_loadu_ps(a);
        b_vec = _mm512_loadu_ps(b);
        a += 16, b += 16, n -= 16;
    }
    __m512 m_vec = _mm512_mul_ps(_mm512_add_ps(a_vec, b_vec), _mm512_set1_ps(0.5f));
    __mmask16 nonzero_mask_a = _mm512_cmp_ps_mask(a_vec, epsilon_vec, _CMP_GE_OQ);
    __mmask16 nonzero_mask_b = _mm512_cmp_ps_mask(b_vec, epsilon_vec, _CMP_GE_OQ);
    __mmask16 nonzero_mask = nonzero_mask_a & nonzero_mask_b;
    __m512 m_recip_approx = _mm512_rcp14_ps(m_vec);
    __m512 ratio_a_vec = _mm512_mul_ps(a_vec, m_recip_approx);
    __m512 ratio_b_vec = _mm512_mul_ps(b_vec, m_recip_approx);
    __m512 log_ratio_a_vec = _simsimd_log2_f32_skylake(ratio_a_vec);
    __m512 log_ratio_b_vec = _simsimd_log2_f32_skylake(ratio_b_vec);
    sum_a_vec = _mm512_maskz_fmadd_ps(nonzero_mask, a_vec, log_ratio_a_vec, sum_a_vec);
    sum_b_vec = _mm512_maskz_fmadd_ps(nonzero_mask, b_vec, log_ratio_b_vec, sum_b_vec);
    if (n) goto simsimd_js_f32_skylake_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = _mm512_reduce_add_ps(_mm512_add_ps(sum_a_vec, sum_b_vec));
    sum *= log2_normalizer / 2;
    *result = _simsimd_sqrt_f32_haswell(sum);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_HASWELL

#if SIMSIMD_TARGET_SAPPHIRE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512fp16")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512fp16"))), apply_to = function)

SIMSIMD_INTERNAL __m512h _simsimd_log2_f16_sapphire(__m512h x) {
    // Extract the exponent and mantissa
    __m512h one = _mm512_set1_ph((simsimd_f16_t)1);
    __m512h e = _mm512_getexp_ph(x);
    __m512h m = _mm512_getmant_ph(x, _MM_MANT_NORM_1_2, _MM_MANT_SIGN_src);

    // Compute the polynomial using Horner's method
    __m512h p = _mm512_set1_ph((simsimd_f16_t)-3.4436006e-2f);
    p = _mm512_fmadd_ph(m, p, _mm512_set1_ph((simsimd_f16_t)3.1821337e-1f));
    p = _mm512_fmadd_ph(m, p, _mm512_set1_ph((simsimd_f16_t)-1.2315303f));
    p = _mm512_fmadd_ph(m, p, _mm512_set1_ph((simsimd_f16_t)2.5988452f));
    p = _mm512_fmadd_ph(m, p, _mm512_set1_ph((simsimd_f16_t)-3.3241990f));
    p = _mm512_fmadd_ph(m, p, _mm512_set1_ph((simsimd_f16_t)3.1157899f));

    return _mm512_add_ph(_mm512_mul_ph(p, _mm512_sub_ph(m, one)), e);
}

SIMSIMD_PUBLIC void simsimd_kl_f16_sapphire(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_size_t n,
                                            simsimd_distance_t *result) {
    __m512h sum_vec = _mm512_setzero_ph();
    __m512h epsilon_vec = _mm512_set1_ph((simsimd_f16_t)SIMSIMD_F16_DIVISION_EPSILON);
    __m512h a_vec, b_vec;

simsimd_kl_f16_sapphire_cycle:
    if (n < 32) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, n);
        a_vec = _mm512_maskz_add_ph(mask, _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, a)), epsilon_vec);
        b_vec = _mm512_maskz_add_ph(mask, _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, b)), epsilon_vec);
        n = 0;
    }
    else {
        a_vec = _mm512_add_ph(_mm512_castsi512_ph(_mm512_loadu_epi16(a)), epsilon_vec);
        b_vec = _mm512_add_ph(_mm512_castsi512_ph(_mm512_loadu_epi16(b)), epsilon_vec);
        a += 32, b += 32, n -= 32;
    }
    __m512h ratio_vec = _mm512_div_ph(a_vec, b_vec);
    __m512h log_ratio_vec = _simsimd_log2_f16_sapphire(ratio_vec);
    __m512h prod_vec = _mm512_mul_ph(a_vec, log_ratio_vec);
    sum_vec = _mm512_add_ph(sum_vec, prod_vec);
    if (n) goto simsimd_kl_f16_sapphire_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    *result = _mm512_reduce_add_ph(sum_vec) * log2_normalizer;
}

SIMSIMD_PUBLIC void simsimd_js_f16_sapphire(simsimd_f16_t const *a, simsimd_f16_t const *b, simsimd_size_t n,
                                            simsimd_distance_t *result) {
    __m512h sum_a_vec = _mm512_setzero_ph();
    __m512h sum_b_vec = _mm512_setzero_ph();
    __m512h epsilon_vec = _mm512_set1_ph((simsimd_f16_t)SIMSIMD_F16_DIVISION_EPSILON);
    __m512h a_vec, b_vec;

simsimd_js_f16_sapphire_cycle:
    if (n < 32) {
        __mmask32 mask = (__mmask32)_bzhi_u32(0xFFFFFFFF, n);
        a_vec = _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, a));
        b_vec = _mm512_castsi512_ph(_mm512_maskz_loadu_epi16(mask, b));
        n = 0;
    }
    else {
        a_vec = _mm512_castsi512_ph(_mm512_loadu_epi16(a));
        b_vec = _mm512_castsi512_ph(_mm512_loadu_epi16(b));
        a += 32, b += 32, n -= 32;
    }
    __m512h m_vec = _mm512_mul_ph(_mm512_add_ph(a_vec, b_vec), _mm512_set1_ph((simsimd_f16_t)0.5f));
    __mmask32 nonzero_mask_a = _mm512_cmp_ph_mask(a_vec, epsilon_vec, _CMP_GE_OQ);
    __mmask32 nonzero_mask_b = _mm512_cmp_ph_mask(b_vec, epsilon_vec, _CMP_GE_OQ);
    __mmask32 nonzero_mask = nonzero_mask_a & nonzero_mask_b;
    __m512h m_recip_approx = _mm512_rcp_ph(m_vec);
    __m512h ratio_a_vec = _mm512_mul_ph(a_vec, m_recip_approx);
    __m512h ratio_b_vec = _mm512_mul_ph(b_vec, m_recip_approx);
    __m512h log_ratio_a_vec = _simsimd_log2_f16_sapphire(ratio_a_vec);
    __m512h log_ratio_b_vec = _simsimd_log2_f16_sapphire(ratio_b_vec);
    sum_a_vec = _mm512_maskz_fmadd_ph(nonzero_mask, a_vec, log_ratio_a_vec, sum_a_vec);
    sum_b_vec = _mm512_maskz_fmadd_ph(nonzero_mask, b_vec, log_ratio_b_vec, sum_b_vec);
    if (n) goto simsimd_js_f16_sapphire_cycle;

    simsimd_f32_t log2_normalizer = 0.693147181f;
    simsimd_f32_t sum = _mm512_reduce_add_ph(_mm512_add_ph(sum_a_vec, sum_b_vec));
    sum *= log2_normalizer / 2;
    *result = _simsimd_sqrt_f32_haswell(sum);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SAPPHIRE
#endif // _SIMSIMD_TARGET_X86

#ifdef __cplusplus
}
#endif

#endif
