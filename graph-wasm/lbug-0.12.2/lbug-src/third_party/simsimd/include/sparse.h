/**
 *  @file       sparse.h
 *  @brief      SIMD-accelerated functions for Sparse Vectors.
 *  @author     Ash Vardanian
 *  @date       March 21, 2024
 *
 *  Contains:
 *  - Set Intersection ~ Jaccard Distance
 *  - Sparse Dot Products, outputting the count and weighted product
 *
 *  For datatypes:
 *  - u16: for vocabularies under 64 thousand tokens
 *  - u32: for vocabularies under 4 billion tokens
 *  - u16 indicies + i16 weights: for weighted word counts
 *  - u16 indicies + bf16 weights: for sparse matrices
 *
 *  For hardware architectures:
 *  - x86: Ice Lake, Turin
 *  - Arm: SVE2
 *
 *  Interestingly, to implement sparse distances and products, the most important function
 *  is analogous to `std::set_intersection`, that outputs the intersection of two sorted
 *  sequences. The naive implementation of that function would look like:
 *
 *      std::size_t intersection_size = 0;
 *      while (i != a_length && j != b_length) {
 *          scalar_t ai = a[i], bj = b[j];
 *          intersection_size += ai == bj;
 *          i += ai < bj;
 *          j += ai >= bj;
 *      }
 *
 *  Assuming we are dealing with sparse arrays, most of the time we are just evaluating
 *  branches and skipping entries. So what if we could skip multiple entries at a time
 *  searching for the next chunk, where an intersection is possible. For weighted arrays:
 *
 *      double product = 0;
 *      while (i != a_length && j != b_length) {
 *          scalar_t ai = a[i], bj = b[j];
 *          product += ai == bj ? a_weights[i] * b_weights[j] : 0;
 *          i += ai < bj;
 *          j += ai >= bj;
 *      }
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 */
#ifndef SIMSIMD_SPARSE_H
#define SIMSIMD_SPARSE_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

/*  Implements the serial set intersection algorithm, similar to `std::set_intersection in C++ STL`,
 *  but uses clever galloping logic, if the arrays significantly differ in size.
 */
SIMSIMD_PUBLIC void simsimd_intersect_u16_serial(     //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_intersect_u32_serial(     //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_counts_u16_serial(                //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                 //
    simsimd_i16_t const *a_weights, simsimd_i16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,               //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_weights_u16_serial(                 //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                   //
    simsimd_bf16_t const *a_weights, simsimd_bf16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,                 //
    simsimd_distance_t *results);

/*  Implements the most naive set intersection algorithm, similar to `std::set_intersection in C++ STL`,
 *  naively enumerating the elements of two arrays.
 */
SIMSIMD_PUBLIC void simsimd_intersect_u16_accurate(   //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_intersect_u32_accurate(   //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_counts_u16_accurate(              //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                 //
    simsimd_i16_t const *a_weights, simsimd_i16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,               //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_weights_u16_accurate(               //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                   //
    simsimd_bf16_t const *a_weights, simsimd_bf16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,                 //
    simsimd_distance_t *results);

/*  SIMD-powered backends for Arm SVE, mostly using 32-bit arithmetic over variable-length platform-defined word sizes.
 *  Designed for Arm Graviton 3, Microsoft Cobalt, as well as Nvidia Grace and newer Ampere Altra CPUs.
 */
SIMSIMD_PUBLIC void simsimd_intersect_u16_sve2(       //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_intersect_u32_sve2(       //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_counts_u16_sve2(                  //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                 //
    simsimd_i16_t const *a_weights, simsimd_i16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,               //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_weights_u16_sve2(                   //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                   //
    simsimd_bf16_t const *a_weights, simsimd_bf16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,                 //
    simsimd_distance_t *results);

/*  SIMD-powered backends for various generations of AVX512 CPUs.
 *  Skylake is handy, as it supports masked loads and other operations, avoiding the need for the tail loop.
 *  Ice Lake, however, is needed even for the most basic kernels to perform integer matching.
 */
SIMSIMD_PUBLIC void simsimd_intersect_u16_ice(        //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_intersect_u32_ice(        //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);

/*  SIMD-powered backends for AMD Turin CPUs with cheap VP2INTERSECT instructions.
 *  On the Intel side, only mobile Tiger Lake support them, but have prohibitively high latency.
 */
SIMSIMD_PUBLIC void simsimd_intersect_u16_turin(      //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_intersect_u32_turin(      //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_counts_u16_turin(                 //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                 //
    simsimd_i16_t const *a_weights, simsimd_i16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,               //
    simsimd_distance_t *results);
SIMSIMD_PUBLIC void simsimd_spdot_weights_u16_turin(                  //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                   //
    simsimd_bf16_t const *a_weights, simsimd_bf16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,                 //
    simsimd_distance_t *results);

#define SIMSIMD_MAKE_INTERSECT_LINEAR(name, input_type, counter_type)                                  \
    SIMSIMD_PUBLIC void simsimd_intersect_##input_type##_##name(                                       \
        simsimd_##input_type##_t const *a, simsimd_##input_type##_t const *b, simsimd_size_t a_length, \
        simsimd_size_t b_length, simsimd_distance_t *result) {                                         \
        simsimd_##counter_type##_t intersection_size = 0;                                              \
        simsimd_size_t i = 0, j = 0;                                                                   \
        while (i != a_length && j != b_length) {                                                       \
            simsimd_##input_type##_t ai = a[i];                                                        \
            simsimd_##input_type##_t bj = b[j];                                                        \
            intersection_size += ai == bj;                                                             \
            i += ai < bj;                                                                              \
            j += ai >= bj;                                                                             \
        }                                                                                              \
        *result = intersection_size;                                                                   \
    }

SIMSIMD_MAKE_INTERSECT_LINEAR(accurate, u16, size) // simsimd_intersect_u16_accurate
SIMSIMD_MAKE_INTERSECT_LINEAR(accurate, u32, size) // simsimd_intersect_u32_accurate

#define SIMSIMD_MAKE_INTERSECT_WEIGHTED(name, variation, input_type, counter_type, weight_type, accumulator_type, \
                                        load_and_convert)                                                         \
    SIMSIMD_PUBLIC void simsimd_##variation##_##input_type##_##name(                                              \
        simsimd_##input_type##_t const *a, simsimd_##input_type##_t const *b,                                     \
        simsimd_##weight_type##_t const *a_weights, simsimd_##weight_type##_t const *b_weights,                   \
        simsimd_size_t a_length, simsimd_size_t b_length, simsimd_distance_t *results) {                          \
        simsimd_##counter_type##_t intersection_size = 0;                                                         \
        simsimd_##accumulator_type##_t weights_product = 0;                                                       \
        simsimd_size_t i = 0, j = 0;                                                                              \
        while (i != a_length && j != b_length) {                                                                  \
            simsimd_##input_type##_t ai = a[i];                                                                   \
            simsimd_##input_type##_t bj = b[j];                                                                   \
            int matches = ai == bj;                                                                               \
            simsimd_##counter_type##_t awi = load_and_convert(a_weights + i);                                     \
            simsimd_##counter_type##_t bwi = load_and_convert(b_weights + i);                                     \
            weights_product += matches * awi * bwi;                                                               \
            intersection_size += matches;                                                                         \
            i += ai < bj;                                                                                         \
            j += ai >= bj;                                                                                        \
        }                                                                                                         \
        results[0] = intersection_size;                                                                           \
        results[1] = weights_product;                                                                             \
    }

SIMSIMD_MAKE_INTERSECT_WEIGHTED(accurate, spdot_counts, u16, size, i16, i64,
                                SIMSIMD_DEREFERENCE) // simsimd_spdot_counts_u16_accurate
SIMSIMD_MAKE_INTERSECT_WEIGHTED(accurate, spdot_weights, u16, size, bf16, f64,
                                SIMSIMD_BF16_TO_F32) // simsimd_spdot_weights_u16_accurate

#define SIMSIMD_MAKE_INTERSECT_GALLOPING(name, input_type, counter_type)                                             \
    SIMSIMD_PUBLIC simsimd_size_t simsimd_galloping_search_##input_type(simsimd_##input_type##_t const *array,       \
                                                                        simsimd_size_t start, simsimd_size_t length, \
                                                                        simsimd_##input_type##_t val) {              \
        simsimd_size_t low = start;                                                                                  \
        simsimd_size_t high = start + 1;                                                                             \
        while (high < length && array[high] < val) {                                                                 \
            low = high;                                                                                              \
            high = (2 * high < length) ? 2 * high : length;                                                          \
        }                                                                                                            \
        while (low < high) {                                                                                         \
            simsimd_size_t mid = low + (high - low) / 2;                                                             \
            if (array[mid] < val) { low = mid + 1; }                                                                 \
            else { high = mid; }                                                                                     \
        }                                                                                                            \
        return low;                                                                                                  \
    }                                                                                                                \
                                                                                                                     \
    SIMSIMD_PUBLIC void simsimd_intersect_##input_type##_##name(                                                     \
        simsimd_##input_type##_t const *shorter, simsimd_##input_type##_t const *longer,                             \
        simsimd_size_t shorter_length, simsimd_size_t longer_length, simsimd_distance_t *result) {                   \
        /* Swap arrays if necessary, as we want "longer" to be larger than "shorter" */                              \
        if (longer_length < shorter_length) {                                                                        \
            simsimd_##input_type##_t const *temp = shorter;                                                          \
            shorter = longer;                                                                                        \
            longer = temp;                                                                                           \
            simsimd_size_t temp_length = shorter_length;                                                             \
            shorter_length = longer_length;                                                                          \
            longer_length = temp_length;                                                                             \
        }                                                                                                            \
                                                                                                                     \
        /* Use the accurate implementation if galloping is not beneficial */                                         \
        if (longer_length < 64 * shorter_length) {                                                                   \
            simsimd_intersect_##input_type##_accurate(shorter, longer, shorter_length, longer_length, result);       \
            return;                                                                                                  \
        }                                                                                                            \
                                                                                                                     \
        /* Perform galloping, shrinking the target range */                                                          \
        simsimd_##counter_type##_t intersection_size = 0;                                                            \
        simsimd_size_t j = 0;                                                                                        \
        for (simsimd_size_t i = 0; i < shorter_length; ++i) {                                                        \
            simsimd_##input_type##_t shorter_i = shorter[i];                                                         \
            j = simsimd_galloping_search_##input_type(longer, j, longer_length, shorter_i);                          \
            if (j < longer_length && longer[j] == shorter_i) { intersection_size++; }                                \
        }                                                                                                            \
        *result = intersection_size;                                                                                 \
    }

SIMSIMD_MAKE_INTERSECT_GALLOPING(serial, u16, size) // simsimd_intersect_u16_serial
SIMSIMD_MAKE_INTERSECT_GALLOPING(serial, u32, size) // simsimd_intersect_u32_serial
SIMSIMD_MAKE_INTERSECT_WEIGHTED(serial, spdot_counts, u16, size, i16, i32,
                                SIMSIMD_DEREFERENCE) // simsimd_spdot_counts_u16_serial
SIMSIMD_MAKE_INTERSECT_WEIGHTED(serial, spdot_weights, u16, size, bf16, f32,
                                SIMSIMD_BF16_TO_F32) // simsimd_spdot_weights_u16_serial

/*  The AVX-512 implementations are inspired by the "Faster-Than-Native Alternatives
 *  for x86 VP2INTERSECT Instructions" paper by Guille Diez-Canas, 2022.
 *
 *      https://github.com/mozonaut/vp2intersect
 *      https://arxiv.org/pdf/2112.06342.pdf
 *
 *  For R&D purposes, it's important to keep the following latencies in mind:
 *
 *   - `_mm512_permutex_epi64` - needs F - 3 cycles latency
 *   - `_mm512_shuffle_epi8` - needs BW - 1 cycle latency
 *   - `_mm512_permutexvar_epi16` - needs BW - 4-6 cycles latency
 *   - `_mm512_permutexvar_epi8` - needs VBMI - 3 cycles latency
 */
#if _SIMSIMD_TARGET_X86
#if SIMSIMD_TARGET_ICE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "lzcnt", "popcnt", "avx512bw", "avx512vbmi2")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,lzcnt,popcnt,avx512bw,avx512vbmi2"))), \
                             apply_to = function)

/**
 *  @brief  Analogous to `_mm512_2intersect_epi16_mask`, but compatible with Ice Lake CPUs,
 *          slightly faster than the native Tiger Lake implementation, but returns only one mask.
 */
SIMSIMD_INTERNAL simsimd_u32_t _simsimd_intersect_u16x32_ice(__m512i a, __m512i b) {
    __m512i a1 = _mm512_alignr_epi32(a, a, 4);
    __m512i a2 = _mm512_alignr_epi32(a, a, 8);
    __m512i a3 = _mm512_alignr_epi32(a, a, 12);

    __m512i b1 = _mm512_shuffle_epi32(b, _MM_PERM_ADCB);
    __m512i b2 = _mm512_shuffle_epi32(b, _MM_PERM_BADC);
    __m512i b3 = _mm512_shuffle_epi32(b, _MM_PERM_CBAD);

    __m512i b01 = _mm512_shrdi_epi32(b, b, 16);
    __m512i b11 = _mm512_shrdi_epi32(b1, b1, 16);
    __m512i b21 = _mm512_shrdi_epi32(b2, b2, 16);
    __m512i b31 = _mm512_shrdi_epi32(b3, b3, 16);

    __mmask32 nm00 = _mm512_cmpneq_epi16_mask(a, b);
    __mmask32 nm01 = _mm512_cmpneq_epi16_mask(a1, b);
    __mmask32 nm02 = _mm512_cmpneq_epi16_mask(a2, b);
    __mmask32 nm03 = _mm512_cmpneq_epi16_mask(a3, b);

    __mmask32 nm10 = _mm512_mask_cmpneq_epi16_mask(nm00, a, b01);
    __mmask32 nm11 = _mm512_mask_cmpneq_epi16_mask(nm01, a1, b01);
    __mmask32 nm12 = _mm512_mask_cmpneq_epi16_mask(nm02, a2, b01);
    __mmask32 nm13 = _mm512_mask_cmpneq_epi16_mask(nm03, a3, b01);

    __mmask32 nm20 = _mm512_mask_cmpneq_epi16_mask(nm10, a, b1);
    __mmask32 nm21 = _mm512_mask_cmpneq_epi16_mask(nm11, a1, b1);
    __mmask32 nm22 = _mm512_mask_cmpneq_epi16_mask(nm12, a2, b1);
    __mmask32 nm23 = _mm512_mask_cmpneq_epi16_mask(nm13, a3, b1);

    __mmask32 nm30 = _mm512_mask_cmpneq_epi16_mask(nm20, a, b11);
    __mmask32 nm31 = _mm512_mask_cmpneq_epi16_mask(nm21, a1, b11);
    __mmask32 nm32 = _mm512_mask_cmpneq_epi16_mask(nm22, a2, b11);
    __mmask32 nm33 = _mm512_mask_cmpneq_epi16_mask(nm23, a3, b11);

    __mmask32 nm40 = _mm512_mask_cmpneq_epi16_mask(nm30, a, b2);
    __mmask32 nm41 = _mm512_mask_cmpneq_epi16_mask(nm31, a1, b2);
    __mmask32 nm42 = _mm512_mask_cmpneq_epi16_mask(nm32, a2, b2);
    __mmask32 nm43 = _mm512_mask_cmpneq_epi16_mask(nm33, a3, b2);

    __mmask32 nm50 = _mm512_mask_cmpneq_epi16_mask(nm40, a, b21);
    __mmask32 nm51 = _mm512_mask_cmpneq_epi16_mask(nm41, a1, b21);
    __mmask32 nm52 = _mm512_mask_cmpneq_epi16_mask(nm42, a2, b21);
    __mmask32 nm53 = _mm512_mask_cmpneq_epi16_mask(nm43, a3, b21);

    __mmask32 nm60 = _mm512_mask_cmpneq_epi16_mask(nm50, a, b3);
    __mmask32 nm61 = _mm512_mask_cmpneq_epi16_mask(nm51, a1, b3);
    __mmask32 nm62 = _mm512_mask_cmpneq_epi16_mask(nm52, a2, b3);
    __mmask32 nm63 = _mm512_mask_cmpneq_epi16_mask(nm53, a3, b3);

    __mmask32 nm70 = _mm512_mask_cmpneq_epi16_mask(nm60, a, b31);
    __mmask32 nm71 = _mm512_mask_cmpneq_epi16_mask(nm61, a1, b31);
    __mmask32 nm72 = _mm512_mask_cmpneq_epi16_mask(nm62, a2, b31);
    __mmask32 nm73 = _mm512_mask_cmpneq_epi16_mask(nm63, a3, b31);

    return ~(simsimd_u32_t)(nm70 & simsimd_u32_rol(nm71, 8) & simsimd_u32_rol(nm72, 16) & simsimd_u32_ror(nm73, 8));
}

/**
 *  @brief  Analogous to `_mm512_2intersect_epi32`, but compatible with Ice Lake CPUs,
 *          slightly faster than the native Tiger Lake implementation, but returns only one mask.
 *
 *  Some latencies to keep in mind:
 *
 *  - `_mm512_shuffle_epi32` - "VPSHUFD (ZMM, ZMM, I8)":
 *      - 1 cycle latency on Ice Lake: 1*p5
 *      - 1 cycle latency on Genoa: 1*FP123
 *  - `_mm512_mask_cmpneq_epi32_mask` - "VPCMPD (K, ZMM, ZMM, I8)":
 *      - 3 cycle latency on Ice Lake: 1*p5
 *      - 1 cycle latency on Genoa: 1*FP01
 *  - `_mm512_alignr_epi32` - "VPALIGNR (ZMM, ZMM, ZMM, I8)":
 *      - 1 cycle latency on Ice Lake: 1*p5
 *      - 2 cycle latency on Genoa: 1*FP12
 *  - `_mm512_conflict_epi32` - "VPCONFLICTD (ZMM, ZMM)":
 *      - up to 26 cycles latency on Ice Lake: 11*p0+9*p05+17*p5
 *      - up to 7 cycle latency on Genoa: 1*FP01+1*FP12
 */
SIMSIMD_INTERNAL simsimd_u16_t _simsimd_intersect_u32x16_ice(__m512i a, __m512i b) {
    __m512i a1 = _mm512_alignr_epi32(a, a, 4);
    __m512i b1 = _mm512_shuffle_epi32(b, _MM_PERM_ADCB);
    __mmask16 nm00 = _mm512_cmpneq_epi32_mask(a, b);

    __m512i a2 = _mm512_alignr_epi32(a, a, 8);
    __m512i a3 = _mm512_alignr_epi32(a, a, 12);
    __mmask16 nm01 = _mm512_cmpneq_epi32_mask(a1, b);
    __mmask16 nm02 = _mm512_cmpneq_epi32_mask(a2, b);

    __mmask16 nm03 = _mm512_cmpneq_epi32_mask(a3, b);
    __mmask16 nm10 = _mm512_mask_cmpneq_epi32_mask(nm00, a, b1);
    __mmask16 nm11 = _mm512_mask_cmpneq_epi32_mask(nm01, a1, b1);

    __m512i b2 = _mm512_shuffle_epi32(b, _MM_PERM_BADC);
    __mmask16 nm12 = _mm512_mask_cmpneq_epi32_mask(nm02, a2, b1);
    __mmask16 nm13 = _mm512_mask_cmpneq_epi32_mask(nm03, a3, b1);
    __mmask16 nm20 = _mm512_mask_cmpneq_epi32_mask(nm10, a, b2);

    __m512i b3 = _mm512_shuffle_epi32(b, _MM_PERM_CBAD);
    __mmask16 nm21 = _mm512_mask_cmpneq_epi32_mask(nm11, a1, b2);
    __mmask16 nm22 = _mm512_mask_cmpneq_epi32_mask(nm12, a2, b2);
    __mmask16 nm23 = _mm512_mask_cmpneq_epi32_mask(nm13, a3, b2);

    __mmask16 nm0 = _mm512_mask_cmpneq_epi32_mask(nm20, a, b3);
    __mmask16 nm1 = _mm512_mask_cmpneq_epi32_mask(nm21, a1, b3);
    __mmask16 nm2 = _mm512_mask_cmpneq_epi32_mask(nm22, a2, b3);
    __mmask16 nm3 = _mm512_mask_cmpneq_epi32_mask(nm23, a3, b3);

    return ~(simsimd_u16_t)(nm0 & simsimd_u16_rol(nm1, 4) & simsimd_u16_rol(nm2, 8) & simsimd_u16_ror(nm3, 4));
}

SIMSIMD_PUBLIC void simsimd_intersect_u16_ice(        //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 64 && b_length < 64) {
        simsimd_intersect_u16_serial(a, b, a_length, b_length, results);
        return;
    }

    simsimd_u16_t const *const a_end = a + a_length;
    simsimd_u16_t const *const b_end = b + b_length;
    simsimd_size_t c = 0;
    union vec_t {
        __m512i zmm;
        simsimd_u16_t u16[32];
        simsimd_u8_t u8[64];
    } a_vec, b_vec;

    while (a + 32 < a_end && b + 32 < b_end) {
        a_vec.zmm = _mm512_loadu_si512((__m512i const *)a);
        b_vec.zmm = _mm512_loadu_si512((__m512i const *)b);

        // Intersecting registers with `_simsimd_intersect_u16x32_ice` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = a_vec.u16[31];
        simsimd_u16_t b_min = b_vec.u16[0];
        simsimd_u16_t b_max = b_vec.u16[31];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 64 < a_end) {
            a += 32;
            a_vec.zmm = _mm512_loadu_si512((__m512i const *)a);
            a_max = a_vec.u16[31];
        }
        a_min = a_vec.u16[0];
        while (b_max < a_min && b + 64 < b_end) {
            b += 32;
            b_vec.zmm = _mm512_loadu_si512((__m512i const *)b);
            b_max = b_vec.u16[31];
        }
        b_min = b_vec.u16[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        __mmask32 a_matches = _simsimd_intersect_u16x32_ice(a_vec.zmm, b_vec.zmm);

        // The paper also contained a very nice procedure for exporting the matches,
        // but we don't need it here:
        //      _mm512_mask_compressstoreu_epi16(c, a_matches, a_vec);
        c += _mm_popcnt_u32(a_matches); // MSVC has no `_popcnt32`

        __m512i a_last_broadcasted = _mm512_set1_epi16(*(short const *)&a_max);
        __m512i b_last_broadcasted = _mm512_set1_epi16(*(short const *)&b_max);
        __mmask32 a_step_mask = _mm512_cmple_epu16_mask(a_vec.zmm, b_last_broadcasted);
        __mmask32 b_step_mask = _mm512_cmple_epu16_mask(b_vec.zmm, a_last_broadcasted);
        a += 32 - _lzcnt_u32((simsimd_u32_t)a_step_mask);
        b += 32 - _lzcnt_u32((simsimd_u32_t)b_step_mask);
    }

    simsimd_intersect_u16_serial(a, b, a_end - a, b_end - b, results);
    *results += c;
}

SIMSIMD_PUBLIC void simsimd_intersect_u32_ice(        //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 32 && b_length < 32) {
        simsimd_intersect_u32_serial(a, b, a_length, b_length, results);
        return;
    }

    simsimd_u32_t const *const a_end = a + a_length;
    simsimd_u32_t const *const b_end = b + b_length;
    simsimd_size_t c = 0;
    union vec_t {
        __m512i zmm;
        simsimd_u32_t u32[16];
        simsimd_u8_t u8[64];
    } a_vec, b_vec;

    while (a + 16 < a_end && b + 16 < b_end) {
        a_vec.zmm = _mm512_loadu_si512((__m512i const *)a);
        b_vec.zmm = _mm512_loadu_si512((__m512i const *)b);

        // Intersecting registers with `_simsimd_intersect_u32x16_ice` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u32_t a_min;
        simsimd_u32_t a_max = a_vec.u32[15];
        simsimd_u32_t b_min = b_vec.u32[0];
        simsimd_u32_t b_max = b_vec.u32[15];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 32 < a_end) {
            a += 16;
            a_vec.zmm = _mm512_loadu_si512((__m512i const *)a);
            a_max = a_vec.u32[15];
        }
        a_min = a_vec.u32[0];
        while (b_max < a_min && b + 32 < b_end) {
            b += 16;
            b_vec.zmm = _mm512_loadu_si512((__m512i const *)b);
            b_max = b_vec.u32[15];
        }
        b_min = b_vec.u32[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        __mmask16 a_matches = _simsimd_intersect_u32x16_ice(a_vec.zmm, b_vec.zmm);

        // The paper also contained a very nice procedure for exporting the matches,
        // but we don't need it here:
        //      _mm512_mask_compressstoreu_epi32(c, a_matches, a_vec);
        c += _mm_popcnt_u32(a_matches); // MSVC has no `_popcnt32`

        __m512i a_last_broadcasted = _mm512_set1_epi32(*(int const *)&a_max);
        __m512i b_last_broadcasted = _mm512_set1_epi32(*(int const *)&b_max);
        __mmask16 a_step_mask = _mm512_cmple_epu32_mask(a_vec.zmm, b_last_broadcasted);
        __mmask16 b_step_mask = _mm512_cmple_epu32_mask(b_vec.zmm, a_last_broadcasted);
        a += 32 - _lzcnt_u32((simsimd_u32_t)a_step_mask);
        b += 32 - _lzcnt_u32((simsimd_u32_t)b_step_mask);
    }

    simsimd_intersect_u32_serial(a, b, a_end - a, b_end - b, results);
    *results += c;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_ICE

#if SIMSIMD_TARGET_TURIN
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "lzcnt", "popcnt", "avx512bw", "avx512vbmi2", "avx512bf16", \
                   "avx512vnni", "avx512vp2intersect")
#pragma clang attribute push(                                                                                       \
    __attribute__((target(                                                                                          \
        "avx2,avx512f,avx512vl,bmi2,lzcnt,popcnt,avx512bw,avx512vbmi2,avx512bf16,avx512vnni,avx512vp2intersect"))), \
    apply_to = function)

SIMSIMD_PUBLIC void simsimd_intersect_u16_turin(      //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 64 && b_length < 64) {
        simsimd_intersect_u16_serial(a, b, a_length, b_length, results);
        return;
    }

    //! There is no such thing as `_mm512_2intersect_epi16`, only the 32-bit variant!
    //! So instead of jumping through 32 entries at a time, like on Ice Lake, we will
    //! step through 16 entries at a time.
    simsimd_u16_t const *const a_end = a + a_length;
    simsimd_u16_t const *const b_end = b + b_length;
    simsimd_size_t c = 0;
    union vec_t {
        __m256i ymm;
        simsimd_u16_t u16[16];
        simsimd_u8_t u8[32];
    } a_vec, b_vec;

    while (a + 16 < a_end && b + 16 < b_end) {
        a_vec.ymm = _mm256_lddqu_si256((__m256i const *)a);
        b_vec.ymm = _mm256_lddqu_si256((__m256i const *)b);

        // Intersecting registers with `_mm512_2intersect_epi16_mask` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = a_vec.u16[15];
        simsimd_u16_t b_min = b_vec.u16[0];
        simsimd_u16_t b_max = b_vec.u16[15];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 32 < a_end) {
            a += 16;
            a_vec.ymm = _mm256_lddqu_si256((__m256i const *)a);
            a_max = a_vec.u16[15];
        }
        a_min = a_vec.u16[0];
        while (b_max < a_min && b + 32 < b_end) {
            b += 16;
            b_vec.ymm = _mm256_lddqu_si256((__m256i const *)b);
            b_max = b_vec.u16[15];
        }
        b_min = b_vec.u16[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        __m512i a_i32_vec = _mm512_cvtepu16_epi32(a_vec.ymm);
        __m512i b_i32_vec = _mm512_cvtepu16_epi32(b_vec.ymm);
        __mmask16 a_matches_any_in_b, b_matches_any_in_a;
        _mm512_2intersect_epi32(a_i32_vec, b_i32_vec, &a_matches_any_in_b, &b_matches_any_in_a);

        // The paper also contained a very nice procedure for exporting the matches,
        // but we don't need it here:
        //      _mm512_mask_compressstoreu_epi16(c, a_matches_any_in_b, a_vec);
        c += _mm_popcnt_u32(a_matches_any_in_b); // MSVC has no `_popcnt32`

        __m256i a_last_broadcasted = _mm256_set1_epi16(*(short const *)&a_max);
        __m256i b_last_broadcasted = _mm256_set1_epi16(*(short const *)&b_max);
        __mmask16 a_step_mask = _mm256_cmple_epu16_mask(a_vec.ymm, b_last_broadcasted);
        __mmask16 b_step_mask = _mm256_cmple_epu16_mask(b_vec.ymm, a_last_broadcasted);
        a += 32 - _lzcnt_u32((simsimd_u32_t)a_step_mask); //? Is this correct? Needs testing!
        b += 32 - _lzcnt_u32((simsimd_u32_t)b_step_mask);
    }

    simsimd_intersect_u16_serial(a, b, a_end - a, b_end - b, results);
    *results += c;
}

SIMSIMD_PUBLIC void simsimd_intersect_u32_turin(      //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 32 && b_length < 32) {
        simsimd_intersect_u32_serial(a, b, a_length, b_length, results);
        return;
    }

    simsimd_u32_t const *const a_end = a + a_length;
    simsimd_u32_t const *const b_end = b + b_length;
    simsimd_size_t c = 0;
    union vec_t {
        __m512i zmm;
        simsimd_u32_t u32[16];
        simsimd_u8_t u8[64];
    } a_vec, b_vec;

    while (a + 16 < a_end && b + 16 < b_end) {
        a_vec.zmm = _mm512_loadu_si512((__m512i const *)a);
        b_vec.zmm = _mm512_loadu_si512((__m512i const *)b);

        // Intersecting registers with `_mm512_2intersect_epi32` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u32_t a_min;
        simsimd_u32_t a_max = a_vec.u32[15];
        simsimd_u32_t b_min = b_vec.u32[0];
        simsimd_u32_t b_max = b_vec.u32[15];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 32 < a_end) {
            a += 16;
            a_vec.zmm = _mm512_loadu_si512((__m512i const *)a);
            a_max = a_vec.u32[15];
        }
        a_min = a_vec.u32[0];
        while (b_max < a_min && b + 32 < b_end) {
            b += 16;
            b_vec.zmm = _mm512_loadu_si512((__m512i const *)b);
            b_max = b_vec.u32[15];
        }
        b_min = b_vec.u32[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        __mmask16 a_matches_any_in_b, b_matches_any_in_a;
        _mm512_2intersect_epi32(a_vec.zmm, b_vec.zmm, &a_matches_any_in_b, &b_matches_any_in_a);

        // The paper also contained a very nice procedure for exporting the matches,
        // but we don't need it here:
        //      _mm512_mask_compressstoreu_epi32(c, a_matches_any_in_b, a_vec);
        c += _mm_popcnt_u32(a_matches_any_in_b); // MSVC has no `_popcnt32`

        __m512i a_last_broadcasted = _mm512_set1_epi32(*(int const *)&a_max);
        __m512i b_last_broadcasted = _mm512_set1_epi32(*(int const *)&b_max);
        __mmask16 a_step_mask = _mm512_cmple_epu32_mask(a_vec.zmm, b_last_broadcasted);
        __mmask16 b_step_mask = _mm512_cmple_epu32_mask(b_vec.zmm, a_last_broadcasted);
        a += 32 - _lzcnt_u32((simsimd_u32_t)a_step_mask);
        b += 32 - _lzcnt_u32((simsimd_u32_t)b_step_mask);
    }

    simsimd_intersect_u32_serial(a, b, a_end - a, b_end - b, results);
    *results += c;
}

SIMSIMD_PUBLIC void simsimd_spdot_weights_u16_turin(                  //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                   //
    simsimd_bf16_t const *a_weights, simsimd_bf16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,                 //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 64 && b_length < 64) {
        simsimd_intersect_u16_serial(a, b, a_length, b_length, results);
        return;
    }

    //! There is no such thing as `_mm512_2intersect_epi16`, only the 32-bit variant!
    //! So instead of jumping through 32 entries at a time, like on Ice Lake, we will
    //! step through 16 entries at a time.
    simsimd_u16_t const *const a_end = a + a_length;
    simsimd_u16_t const *const b_end = b + b_length;
    simsimd_size_t intersection_size = 0;
    union vec_t {
        __m256i ymm;
        __m256 ymmps;
        simsimd_u16_t u16[16];
        simsimd_u8_t u8[32];
    } a_vec, b_vec, product_vec;
    product_vec.ymmps = _mm256_setzero_ps();

    while (a + 16 < a_end && b + 16 < b_end) {
        a_vec.ymm = _mm256_lddqu_si256((__m256i const *)a);
        b_vec.ymm = _mm256_lddqu_si256((__m256i const *)b);

        // Intersecting registers with `_mm512_2intersect_epi16_mask` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = a_vec.u16[15];
        simsimd_u16_t b_min = b_vec.u16[0];
        simsimd_u16_t b_max = b_vec.u16[15];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 32 < a_end) {
            a += 16, a_weights += 16;
            a_vec.ymm = _mm256_lddqu_si256((__m256i const *)a);
            a_max = a_vec.u16[15];
        }
        a_min = a_vec.u16[0];
        while (b_max < a_min && b + 32 < b_end) {
            b += 16, b_weights += 16;
            b_vec.ymm = _mm256_lddqu_si256((__m256i const *)b);
            b_max = b_vec.u16[15];
        }
        b_min = b_vec.u16[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        __m512i a_i32_vec = _mm512_cvtepu16_epi32(a_vec.ymm);
        __m512i b_i32_vec = _mm512_cvtepu16_epi32(b_vec.ymm);
        __mmask16 a_matches_any_in_b, b_matches_any_in_a;
        _mm512_2intersect_epi32(a_i32_vec, b_i32_vec, &a_matches_any_in_b, &b_matches_any_in_a);

        // The paper also contained a very nice procedure for exporting the matches,
        // but we don't need it here:
        //      _mm512_mask_compressstoreu_epi16(intersection_size, a_matches_any_in_b, a_vec);
        int a_matches_count_in_b = _mm_popcnt_u32(a_matches_any_in_b); // MSVC has no `_popcnt32`
        intersection_size += a_matches_count_in_b;

        // Load and shift all the relevant weights to the start of the vector before doing the dot product
        if (a_matches_count_in_b) {
            __m256i a_weights_vec = _mm256_lddqu_si256((__m256i const *)a_weights);
            a_weights_vec = _mm256_maskz_compress_epi16(a_matches_any_in_b, a_weights_vec);
            __m256i b_weights_vec = _mm256_lddqu_si256((__m256i const *)b_weights);
            b_weights_vec = _mm256_maskz_compress_epi16(b_matches_any_in_a, b_weights_vec);
            product_vec.ymmps = _mm256_dpbf16_ps(product_vec.ymmps, (__m256bh)a_weights_vec, (__m256bh)b_weights_vec);
        }

        __m256i a_last_broadcasted = _mm256_set1_epi16(*(short const *)&a_max);
        __m256i b_last_broadcasted = _mm256_set1_epi16(*(short const *)&b_max);
        __mmask16 a_step_mask = _mm256_cmple_epu16_mask(a_vec.ymm, b_last_broadcasted);
        __mmask16 b_step_mask = _mm256_cmple_epu16_mask(b_vec.ymm, a_last_broadcasted);
        int a_step = 32 - _lzcnt_u32((simsimd_u32_t)a_step_mask); //? Is this correct? Needs testing!
        int b_step = 32 - _lzcnt_u32((simsimd_u32_t)b_step_mask);
        a += a_step, a_weights += a_step;
        b += b_step, b_weights += b_step;
    }

    simsimd_intersect_u16_serial(a, b, a_end - a, b_end - b, results);
    *results += intersection_size;
}

SIMSIMD_PUBLIC void simsimd_spdot_counts_u16_turin(                 //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                 //
    simsimd_i16_t const *a_weights, simsimd_i16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,               //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 64 && b_length < 64) {
        simsimd_intersect_u16_serial(a, b, a_length, b_length, results);
        return;
    }

    //! There is no such thing as `_mm512_2intersect_epi16`, only the 32-bit variant!
    //! So instead of jumping through 32 entries at a time, like on Ice Lake, we will
    //! step through 16 entries at a time.
    simsimd_u16_t const *const a_end = a + a_length;
    simsimd_u16_t const *const b_end = b + b_length;
    simsimd_size_t intersection_size = 0;
    union vec_t {
        __m256i ymm;
        simsimd_u16_t u16[16];
        simsimd_u8_t u8[32];
    } a_vec, b_vec, product_vec;
    product_vec.ymm = _mm256_setzero_si256();

    while (a + 16 < a_end && b + 16 < b_end) {
        a_vec.ymm = _mm256_lddqu_si256((__m256i const *)a);
        b_vec.ymm = _mm256_lddqu_si256((__m256i const *)b);

        // Intersecting registers with `_mm512_2intersect_epi16_mask` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = a_vec.u16[15];
        simsimd_u16_t b_min = b_vec.u16[0];
        simsimd_u16_t b_max = b_vec.u16[15];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 32 < a_end) {
            a += 16, a_weights += 16;
            a_vec.ymm = _mm256_lddqu_si256((__m256i const *)a);
            a_max = a_vec.u16[15];
        }
        a_min = a_vec.u16[0];
        while (b_max < a_min && b + 32 < b_end) {
            b += 16, b_weights += 16;
            b_vec.ymm = _mm256_lddqu_si256((__m256i const *)b);
            b_max = b_vec.u16[15];
        }
        b_min = b_vec.u16[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        __m512i a_i32_vec = _mm512_cvtepu16_epi32(a_vec.ymm);
        __m512i b_i32_vec = _mm512_cvtepu16_epi32(b_vec.ymm);
        __mmask16 a_matches_any_in_b, b_matches_any_in_a;
        _mm512_2intersect_epi32(a_i32_vec, b_i32_vec, &a_matches_any_in_b, &b_matches_any_in_a);

        // The paper also contained a very nice procedure for exporting the matches,
        // but we don't need it here:
        //      _mm512_mask_compressstoreu_epi16(intersection_size, a_matches_any_in_b, a_vec);
        int a_matches_count_in_b = _mm_popcnt_u32(a_matches_any_in_b); // MSVC has no `_popcnt32`
        intersection_size += a_matches_count_in_b;

        // Load and shift all the relevant weights to the start of the vector before doing the dot product
        if (a_matches_count_in_b) {
            __m256i a_weights_vec = _mm256_lddqu_si256((__m256i const *)a_weights);
            a_weights_vec = _mm256_maskz_compress_epi16(a_matches_any_in_b, a_weights_vec);
            __m256i b_weights_vec = _mm256_lddqu_si256((__m256i const *)b_weights);
            b_weights_vec = _mm256_maskz_compress_epi16(b_matches_any_in_a, b_weights_vec);
            product_vec.ymm = _mm256_dpwssds_epi32(product_vec.ymm, a_weights_vec, b_weights_vec);
        }

        __m256i a_last_broadcasted = _mm256_set1_epi16(*(short const *)&a_max);
        __m256i b_last_broadcasted = _mm256_set1_epi16(*(short const *)&b_max);
        __mmask16 a_step_mask = _mm256_cmple_epu16_mask(a_vec.ymm, b_last_broadcasted);
        __mmask16 b_step_mask = _mm256_cmple_epu16_mask(b_vec.ymm, a_last_broadcasted);
        int a_step = 32 - _lzcnt_u32((simsimd_u32_t)a_step_mask); //? Is this correct? Needs testing!
        int b_step = 32 - _lzcnt_u32((simsimd_u32_t)b_step_mask);
        a += a_step, a_weights += a_step;
        b += b_step, b_weights += b_step;
    }

    simsimd_intersect_u16_serial(a, b, a_end - a, b_end - b, results);
    *results += intersection_size;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_TURIN
#endif // _SIMSIMD_TARGET_X86

#if _SIMSIMD_TARGET_ARM
#if SIMSIMD_TARGET_NEON
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a"))), apply_to = function)

/**
 *  @brief  Uses `vshrn` to produce a bitmask, similar to `movemask` in SSE.
 *  https://community.arm.com/arm-community-blogs/b/infrastructure-solutions-blog/posts/porting-x86-vector-bitmask-optimizations-to-arm-neon
 */
SIMSIMD_INTERNAL simsimd_u64_t _simsimd_u8_to_u4_neon(uint8x16_t vec) {
    return vget_lane_u64(vreinterpret_u64_u8(vshrn_n_u16(vreinterpretq_u16_u8(vec), 4)), 0);
}

SIMSIMD_INTERNAL int _simsimd_clz_u64(simsimd_u64_t x) {
// On GCC and Clang use the builtin, otherwise use the generic implementation
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_clzll(x);
#else
    int n = 0;
    while ((x & 0x8000000000000000ull) == 0) n++, x <<= 1;
    return n;
#endif
}

SIMSIMD_INTERNAL uint32x4_t _simsimd_intersect_u32x4_neon(uint32x4_t a, uint32x4_t b) {
    uint32x4_t b1 = vextq_u32(b, b, 1);
    uint32x4_t b2 = vextq_u32(b, b, 2);
    uint32x4_t b3 = vextq_u32(b, b, 3);
    uint32x4_t nm00 = vceqq_u32(a, b);
    uint32x4_t nm01 = vceqq_u32(a, b1);
    uint32x4_t nm02 = vceqq_u32(a, b2);
    uint32x4_t nm03 = vceqq_u32(a, b3);
    uint32x4_t nm = vorrq_u32(vorrq_u32(nm00, nm01), vorrq_u32(nm02, nm03));
    return nm;
}

SIMSIMD_INTERNAL uint16x8_t _simsimd_intersect_u16x8_neon(uint16x8_t a, uint16x8_t b) {
    uint16x8_t b1 = vextq_u16(b, b, 1);
    uint16x8_t b2 = vextq_u16(b, b, 2);
    uint16x8_t b3 = vextq_u16(b, b, 3);
    uint16x8_t b4 = vextq_u16(b, b, 4);
    uint16x8_t b5 = vextq_u16(b, b, 5);
    uint16x8_t b6 = vextq_u16(b, b, 6);
    uint16x8_t b7 = vextq_u16(b, b, 7);
    uint16x8_t nm00 = vceqq_u16(a, b);
    uint16x8_t nm01 = vceqq_u16(a, b1);
    uint16x8_t nm02 = vceqq_u16(a, b2);
    uint16x8_t nm03 = vceqq_u16(a, b3);
    uint16x8_t nm04 = vceqq_u16(a, b4);
    uint16x8_t nm05 = vceqq_u16(a, b5);
    uint16x8_t nm06 = vceqq_u16(a, b6);
    uint16x8_t nm07 = vceqq_u16(a, b7);
    uint16x8_t nm = vorrq_u16(vorrq_u16(vorrq_u16(nm00, nm01), vorrq_u16(nm02, nm03)),
                              vorrq_u16(vorrq_u16(nm04, nm05), vorrq_u16(nm06, nm07)));
    return nm;
}

SIMSIMD_PUBLIC void simsimd_intersect_u16_neon(       //
    simsimd_u16_t const *a, simsimd_u16_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 32 && b_length < 32) {
        simsimd_intersect_u16_serial(a, b, a_length, b_length, results);
        return;
    }

    simsimd_u16_t const *const a_end = a + a_length;
    simsimd_u16_t const *const b_end = b + b_length;
    union vec_t {
        uint16x8_t u16x8;
        simsimd_u16_t u16[8];
        simsimd_u8_t u8[16];
    } a_vec, b_vec, c_counts_vec;
    c_counts_vec.u16x8 = vdupq_n_u16(0);

    while (a + 8 < a_end && b + 8 < b_end) {
        a_vec.u16x8 = vld1q_u16(a);
        b_vec.u16x8 = vld1q_u16(b);

        // Intersecting registers with `_simsimd_intersect_u16x8_neon` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = a_vec.u16[7];
        simsimd_u16_t b_min = b_vec.u16[0];
        simsimd_u16_t b_max = b_vec.u16[7];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 16 < a_end) {
            a += 8;
            a_vec.u16x8 = vld1q_u16(a);
            a_max = a_vec.u16[7];
        }
        a_min = a_vec.u16[0];
        while (b_max < a_min && b + 16 < b_end) {
            b += 8;
            b_vec.u16x8 = vld1q_u16(b);
            b_max = b_vec.u16[7];
        }
        b_min = b_vec.u16[0];

        // Now we are likely to have some overlap, so we can intersect the registers.
        // We can do it by performing a population count at every cycle, but it's not the cheapest in terms of cycles.
        //
        //      simsimd_u64_t a_matches = __builtin_popcountll(
        //          _simsimd_u8_to_u4_neon(vreinterpretq_u8_u16(
        //              _simsimd_intersect_u16x8_neon(a_vec.u16x8, b_vec.u16x8))));
        //      c += a_matches / 8;
        //
        // Alternatively, we can we can transform match-masks into "ones", accumulate them between the cycles,
        // and merge all together in the end.
        uint16x8_t a_matches = _simsimd_intersect_u16x8_neon(a_vec.u16x8, b_vec.u16x8);
        c_counts_vec.u16x8 = vaddq_u16(c_counts_vec.u16x8, vandq_u16(a_matches, vdupq_n_u16(1)));

        // Counting leading zeros is tricky. On Arm we can use inline Assembly to get the result,
        // but MSVC doesn't support that:
        //
        //      SIMSIMD_INTERNAL int _simsimd_clz_u64(simsimd_u64_t value) {
        //          simsimd_u64_t result;
        //          __asm__("clz %x0, %x1" : "=r"(result) : "r"(value));
        //          return (int)result;
        //      }
        //
        // Alternatively, we can use the `vclz_u32` NEON intrinsic.
        // It will compute the leading zeros number for both `a_step` and `b_step` in parallel.
        uint16x8_t a_last_broadcasted = vdupq_n_u16(a_max);
        uint16x8_t b_last_broadcasted = vdupq_n_u16(b_max);
        simsimd_u64_t a_step = _simsimd_clz_u64(_simsimd_u8_to_u4_neon( //
            vreinterpretq_u8_u16(vcleq_u16(a_vec.u16x8, b_last_broadcasted))));
        simsimd_u64_t b_step = _simsimd_clz_u64(_simsimd_u8_to_u4_neon( //
            vreinterpretq_u8_u16(vcleq_u16(b_vec.u16x8, a_last_broadcasted))));
        a += (64 - a_step) / 8;
        b += (64 - b_step) / 8;
    }

    simsimd_intersect_u16_serial(a, b, a_end - a, b_end - b, results);
    *results += vaddvq_u16(c_counts_vec.u16x8);
}

SIMSIMD_PUBLIC void simsimd_intersect_u32_neon(       //
    simsimd_u32_t const *a, simsimd_u32_t const *b,   //
    simsimd_size_t a_length, simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // The baseline implementation for very small arrays (2 registers or less) can be quite simple:
    if (a_length < 32 && b_length < 32) {
        simsimd_intersect_u32_serial(a, b, a_length, b_length, results);
        return;
    }

    simsimd_u32_t const *const a_end = a + a_length;
    simsimd_u32_t const *const b_end = b + b_length;
    union vec_t {
        uint32x4_t u32x4;
        simsimd_u32_t u32[4];
        simsimd_u8_t u8[16];
    } a_vec, b_vec, c_counts_vec;
    c_counts_vec.u32x4 = vdupq_n_u32(0);

    while (a + 4 < a_end && b + 4 < b_end) {
        a_vec.u32x4 = vld1q_u32(a);
        b_vec.u32x4 = vld1q_u32(b);

        // Intersecting registers with `_simsimd_intersect_u32x4_neon` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u32_t a_min;
        simsimd_u32_t a_max = a_vec.u32[3];
        simsimd_u32_t b_min = b_vec.u32[0];
        simsimd_u32_t b_max = b_vec.u32[3];

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && a + 8 < a_end) {
            a += 4;
            a_vec.u32x4 = vld1q_u32(a);
            a_max = a_vec.u32[3];
        }
        a_min = a_vec.u32[0];
        while (b_max < a_min && b + 8 < b_end) {
            b += 4;
            b_vec.u32x4 = vld1q_u32(b);
            b_max = b_vec.u32[3];
        }
        b_min = b_vec.u32[0];

        // Now we are likely to have some overlap, so we can intersect the registers
        // We can do it by performing a population count at every cycle, but it's not the cheapest in terms of cycles.
        //
        //     simsimd_u64_t a_matches = __builtin_popcountll(
        //         _simsimd_u8_to_u4_neon(vreinterpretq_u8_u32(
        //             _simsimd_intersect_u32x4_neon(a_vec.u32x4, b_vec.u32x4))));
        //     c += a_matches / 16;
        //
        // Alternatively, we can we can transform match-masks into "ones", accumulate them between the cycles,
        // and merge all together in the end.
        uint32x4_t a_matches = _simsimd_intersect_u32x4_neon(a_vec.u32x4, b_vec.u32x4);
        c_counts_vec.u32x4 = vaddq_u32(c_counts_vec.u32x4, vandq_u32(a_matches, vdupq_n_u32(1)));

        uint32x4_t a_last_broadcasted = vdupq_n_u32(a_max);
        uint32x4_t b_last_broadcasted = vdupq_n_u32(b_max);
        simsimd_u64_t a_step = _simsimd_clz_u64(_simsimd_u8_to_u4_neon( //
            vreinterpretq_u8_u32(vcleq_u32(a_vec.u32x4, b_last_broadcasted))));
        simsimd_u64_t b_step = _simsimd_clz_u64(_simsimd_u8_to_u4_neon( //
            vreinterpretq_u8_u32(vcleq_u32(b_vec.u32x4, a_last_broadcasted))));
        a += (64 - a_step) / 16;
        b += (64 - b_step) / 16;
    }

    simsimd_intersect_u32_serial(a, b, a_end - a, b_end - b, results);
    *results += vaddvq_u32(c_counts_vec.u32x4);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON

/*  SVE2 introduces many new integer-oriented instructions, extending some of the NEON functionality
 *  to variable-length SVE registers. Those include "compare multiple" intrinsics:
 *
 *  - `svmatch[_u16]` that matches each scalar in first vector against all members of a 128-bit lane in the second.
 *  - `svhistcnt[_s32]_z` does something similar, performing an inclusive prefix scan.
 *  - `svtbx[_u16]` does extended table lookup
 *
 *  Other notable instructions:
 *
 *  - `DUP`: Broadcast indexed predicate element
 *    https://developer.arm.com/documentation/ddi0602/2021-06/SVE-Instructions/DUP--predicate---Broadcast-indexed-predicate-element-?lang=en
 *  - `SCLAMP` and `UCLAMP`: clamp values, i.e. combined min+max
 *    https://developer.arm.com/documentation/ddi0602/2021-06/SVE-Instructions/SCLAMP--Signed-clamp-to-minimum-maximum-vector-?lang=en
 *    https://developer.arm.com/documentation/ddi0602/2021-06/SVE-Instructions/UCLAMP--Unsigned-clamp-to-minimum-maximum-vector-?lang=en
 *  - `TBLQ`: Table lookup quadword
 *    https://developer.arm.com/documentation/ddi0602/2022-12/SVE-Instructions/TBLQ--Programmable-table-lookup-within-each-quadword-vector-segment--zeroing--?lang=en
 *
 *  Great resources for SVE2 intrinsics:
 *
 *  > ARM’s Scalable Vector Extensions: A Critical Look at SVE2 For Integer Workloads
 *    https://gist.github.com/zingaburga/805669eb891c820bd220418ee3f0d6bd
 */
#if SIMSIMD_TARGET_SVE2
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+sve+sve2")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+sve+sve2"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_intersect_u16_sve2(     //
    simsimd_u16_t const *a, simsimd_u16_t const *b, //
    simsimd_size_t a_length,
    simsimd_size_t b_length, //
    simsimd_distance_t *results) {

    // A single SVE lane is 128 bits wide, so one lane fits 8 values.
    simsimd_size_t const register_size = svcnth();
    simsimd_size_t const lanes_count = register_size / 8;
    simsimd_size_t a_idx = 0, b_idx = 0;
    simsimd_size_t c = 0;

    while (a_idx < a_length && b_idx < b_length) {
        // Load `a_member` and broadcast it, load `b_members_vec` from memory
        svbool_t a_progress = svwhilelt_b16_u64(a_idx, a_length);
        svbool_t b_progress = svwhilelt_b16_u64(b_idx, b_length);
        svuint16_t a_vec = svld1_u16(a_progress, a + a_idx);
        svuint16_t b_vec = svld1_u16(b_progress, b + b_idx);

        // Intersecting registers with `svmatch_u16` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = svlastb(a_progress, a_vec);
        simsimd_u16_t b_min = svlasta(svpfalse_b(), b_vec);
        simsimd_u16_t b_max = svlastb(b_progress, b_vec);

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && (a_idx + register_size) < a_length) {
            a_idx += register_size;
            a_progress = svwhilelt_b16_u64(a_idx, a_length);
            a_vec = svld1_u16(a_progress, a + a_idx);
            a_max = svlastb(a_progress, a_vec);
        }
        a_min = svlasta(svpfalse_b(), a_vec);
        while (b_max < a_min && (b_idx + register_size) < b_length) {
            b_idx += register_size;
            b_progress = svwhilelt_b16_u64(b_idx, b_length);
            b_vec = svld1_u16(b_progress, b + b_idx);
            b_max = svlastb(b_progress, b_vec);
        }
        b_min = svlasta(svpfalse_b(), b_vec);

        // Before we evaluate the intersection size, obfurscating the order in `b_vec`,
        // let's estimate how much we will need to advance the pointers afterwards.
        // For that, we don't even need to broadcast the values in SVE, as the whole
        // register can be compared against a scalar:
        //
        //      svuint16_t a_last_broadcasted =  svdup_n_u16(a_max);
        //      svuint16_t b_last_broadcasted =  svdup_n_u16(b_max);
        svbool_t a_mask = svcmple_n_u16(a_progress, a_vec, b_max);
        svbool_t b_mask = svcmple_n_u16(b_progress, b_vec, a_max);
        simsimd_u64_t a_step = svcntp_b16(a_progress, a_mask);
        simsimd_u64_t b_step = svcntp_b16(b_progress, b_mask);

        // Compare `a_vec` with each lane of `b_vec`
        svbool_t equal_mask = svmatch_u16(a_progress, a_vec, b_vec);
        for (simsimd_size_t i = 1; i < lanes_count; i++) {
            b_vec = svext_u16(b_vec, b_vec, 8);
            equal_mask = svorr_z(svptrue_b16(), equal_mask, svmatch_u16(a_progress, a_vec, b_vec));
        }
        simsimd_size_t equal_count = svcntp_b16(svptrue_b16(), equal_mask);

        // Advance
        a_idx += a_step;
        b_idx += b_step;
        c += equal_count;
    }
    *results = c;
}

SIMSIMD_PUBLIC void simsimd_intersect_u32_sve2(simsimd_u32_t const *a, simsimd_u32_t const *b, simsimd_size_t a_length,
                                               simsimd_size_t b_length, simsimd_distance_t *results) {

    // A single SVE lane is 128 bits wide, so one lane fits 4 values.
    simsimd_size_t const register_size = svcntw();
    simsimd_size_t const lanes_count = register_size / 4;
    simsimd_size_t a_idx = 0, b_idx = 0;
    simsimd_size_t c = 0;

    while (a_idx < a_length && b_idx < b_length) {
        // Load `a_member` and broadcast it, load `b_members_vec` from memory
        svbool_t a_progress = svwhilelt_b32_u64(a_idx, a_length);
        svbool_t b_progress = svwhilelt_b32_u64(b_idx, b_length);
        svuint32_t a_vec = svld1_u32(a_progress, a + a_idx);
        svuint32_t b_vec = svld1_u32(b_progress, b + b_idx);

        // Intersecting registers with `svmatch_u16` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u32_t a_min;
        simsimd_u32_t a_max = svlastb(a_progress, a_vec);
        simsimd_u32_t b_min = svlasta(svpfalse_b(), b_vec);
        simsimd_u32_t b_max = svlastb(b_progress, b_vec);

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && (a_idx + register_size) < a_length) {
            a_idx += register_size;
            a_progress = svwhilelt_b32_u64(a_idx, a_length);
            a_vec = svld1_u32(a_progress, a + a_idx);
            a_max = svlastb(a_progress, a_vec);
        }
        a_min = svlasta(svpfalse_b(), a_vec);
        while (b_max < a_min && (b_idx + register_size) < b_length) {
            b_idx += register_size;
            b_progress = svwhilelt_b32_u64(b_idx, b_length);
            b_vec = svld1_u32(b_progress, b + b_idx);
            b_max = svlastb(b_progress, b_vec);
        }
        b_min = svlasta(svpfalse_b(), b_vec);

        // Before we evaluate the intersection size, obfurscating the order in `b_vec`,
        // let's estimate how much we will need to advance the pointers afterwards.
        // For that, we don't even need to broadcast the values in SVE, as the whole
        // register can be compared against a scalar:
        //
        //      svuint32_t a_last_broadcasted =  svdup_n_u32(a_max);
        //      svuint32_t b_last_broadcasted =  svdup_n_u32(b_max);
        svbool_t a_mask = svcmple_n_u32(a_progress, a_vec, b_max);
        svbool_t b_mask = svcmple_n_u32(b_progress, b_vec, a_max);
        simsimd_u64_t a_step = svcntp_b32(a_progress, a_mask);
        simsimd_u64_t b_step = svcntp_b32(b_progress, b_mask);

        // Comparing `a_vec` with each lane of `b_vec` can't be done with `svmatch`,
        // the same way as in `simsimd_intersect_u16_sve2`, as that instruction is only
        // available for 8-bit and 16-bit integers.
        //
        //      svbool_t equal_mask = svpfalse_b();
        //      for (simsimd_size_t i = 0; i < register_size; i++) {
        //          equal_mask = svorr_z(svptrue_b32(), equal_mask, svcmpeq_u32(a_progress, a_vec, b_vec));
        //          b_vec = svext_u32(b_vec, b_vec, 1);
        //      }
        //      simsimd_size_t equal_count = svcntp_b32(a_progress, equal_mask);
        //
        // Alternatively, one can use histogram instructions, like `svhistcnt_u32_z`.
        // They practically compute the prefix-matching count, which is equivalent to
        // the lower triangle of the row-major intersection matrix.
        // To compute the upper triangle, we can reverse (with `svrev_b32`) the order of
        // elements and repeat the operation, accumulating the results for top and bottom.
        // Let's look at 4x element registers as an example:
        //
        //      ⊐ α = {A, B, C, D}, β = {X, Y, Z, W}:
        //
        //      hist(α, β):           hist(α_rev, β_rev):
        //
        //        X Y Z W               W Z Y X
        //      A 1 0 0 0             D 1 0 0 0
        //      B 1 1 0 0             C 1 1 0 0
        //      C 1 1 1 0             B 1 1 1 0
        //      D 1 1 1 1             A 1 1 1 1
        //
        svuint32_t hist_lower = svhistcnt_u32_z(a_progress, a_vec, b_vec);
        svuint32_t a_rev_vec = svrev_u32(a_vec);
        svuint32_t b_rev_vec = svrev_u32(b_vec);
        svuint32_t hist_upper = svrev_u32(svhistcnt_u32_z(svptrue_b32(), a_rev_vec, b_rev_vec));
        svuint32_t hist = svorr_u32_x(a_progress, hist_lower, hist_upper);
        svbool_t equal_mask = svcmpne_n_u32(a_progress, hist, 0);
        simsimd_size_t equal_count = svcntp_b32(a_progress, equal_mask);

        // Advance
        a_idx += a_step;
        b_idx += b_step;
        c += equal_count;
    }
    *results = c;
}

SIMSIMD_PUBLIC void simsimd_spdot_counts_u16_sve2(                  //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                 //
    simsimd_i16_t const *a_weights, simsimd_i16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,               //
    simsimd_distance_t *results) {

    // A single SVE lane is 128 bits wide, so one lane fits 8 values.
    simsimd_size_t const register_size = svcnth();
    simsimd_size_t const lanes_count = register_size / 8;
    simsimd_size_t a_idx = 0, b_idx = 0;
    svint64_t product_vec = svdupq_n_s64(0, 0);
    simsimd_size_t intersection_size = 0;

    while (a_idx < a_length && b_idx < b_length) {
        // Load `a_member` and broadcast it, load `b_members_vec` from memory
        svbool_t a_progress = svwhilelt_b16_u64(a_idx, a_length);
        svbool_t b_progress = svwhilelt_b16_u64(b_idx, b_length);
        svuint16_t a_vec = svld1_u16(a_progress, a + a_idx);
        svuint16_t b_vec = svld1_u16(b_progress, b + b_idx);

        // Intersecting registers with `svmatch_u16` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = svlastb(a_progress, a_vec);
        simsimd_u16_t b_min = svlasta(svpfalse_b(), b_vec);
        simsimd_u16_t b_max = svlastb(b_progress, b_vec);

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && (a_idx + register_size) < a_length) {
            a_idx += register_size;
            a_progress = svwhilelt_b16_u64(a_idx, a_length);
            a_vec = svld1_u16(a_progress, a + a_idx);
            a_max = svlastb(a_progress, a_vec);
        }
        a_min = svlasta(svpfalse_b(), a_vec);
        while (b_max < a_min && (b_idx + register_size) < b_length) {
            b_idx += register_size;
            b_progress = svwhilelt_b16_u64(b_idx, b_length);
            b_vec = svld1_u16(b_progress, b + b_idx);
            b_max = svlastb(b_progress, b_vec);
        }
        b_min = svlasta(svpfalse_b(), b_vec);

        // Before we evaluate the intersection size, obfurscating the order in `b_vec`,
        // let's estimate how much we will need to advance the pointers afterwards.
        // For that, we don't even need to broadcast the values in SVE, as the whole
        // register can be compared against a scalar:
        //
        //      svuint16_t a_last_broadcasted =  svdup_n_u16(a_max);
        //      svuint16_t b_last_broadcasted =  svdup_n_u16(b_max);
        svbool_t a_mask = svcmple_n_u16(a_progress, a_vec, b_max);
        svbool_t b_mask = svcmple_n_u16(b_progress, b_vec, a_max);
        simsimd_u64_t a_step = svcntp_b16(a_progress, a_mask);
        simsimd_u64_t b_step = svcntp_b16(b_progress, b_mask);

        // Compare `a_vec` with each lane of `b_vec`
        svint16_t a_weights_vec = svld1_s16(a_progress, a_weights + a_idx);
        svint16_t b_weights_vec = svld1_s16(b_progress, b_weights + b_idx);
        for (simsimd_size_t i = 0; i < lanes_count; i++) {
            svbool_t equal_mask = svmatch_u16(a_progress, a_vec, b_vec);
            svint16_t b_equal_weights_vec = svsel_s16(equal_mask, b_weights_vec, svdup_n_s16(0.f));
            product_vec = svdot_s64(product_vec, a_weights_vec, b_equal_weights_vec);
            b_vec = svext_u16(b_vec, b_vec, 8);
            intersection_size += svcntp_b16(svptrue_b16(), equal_mask);
        }

        // Advance
        a_idx += a_step;
        b_idx += b_step;
    }
    results[0] = (simsimd_distance_t)intersection_size;
    results[1] = svaddv_s64(svptrue_b64(), product_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SVE2

#if SIMSIMD_TARGET_SVE2 && SIMSIMD_TARGET_SVE_BF16
#pragma GCC push_options
#pragma GCC target("arch=armv8.6-a+sve+sve2+bf16")
#pragma clang attribute push(__attribute__((target("arch=armv8.6-a+sve+sve2+bf16"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_spdot_weights_u16_sve2(                   //
    simsimd_u16_t const *a, simsimd_u16_t const *b,                   //
    simsimd_bf16_t const *a_weights, simsimd_bf16_t const *b_weights, //
    simsimd_size_t a_length, simsimd_size_t b_length,                 //
    simsimd_distance_t *results) {

    // A single SVE lane is 128 bits wide, so one lane fits 8 values.
    simsimd_size_t const register_size = svcnth();
    simsimd_size_t const lanes_count = register_size / 8;
    simsimd_size_t a_idx = 0, b_idx = 0;
    svfloat32_t product_vec = svdupq_n_f32(0.f, 0.f, 0.f, 0.f);
    simsimd_size_t intersection_size = 0;

    while (a_idx < a_length && b_idx < b_length) {
        // Load `a_member` and broadcast it, load `b_members_vec` from memory
        svbool_t a_progress = svwhilelt_b16_u64(a_idx, a_length);
        svbool_t b_progress = svwhilelt_b16_u64(b_idx, b_length);
        svuint16_t a_vec = svld1_u16(a_progress, a + a_idx);
        svuint16_t b_vec = svld1_u16(b_progress, b + b_idx);

        // Intersecting registers with `svmatch_u16` involves a lot of shuffling
        // and comparisons, so we want to avoid it if the slices don't overlap at all..
        simsimd_u16_t a_min;
        simsimd_u16_t a_max = svlastb(a_progress, a_vec);
        simsimd_u16_t b_min = svlasta(svpfalse_b(), b_vec);
        simsimd_u16_t b_max = svlastb(b_progress, b_vec);

        // If the slices don't overlap, advance the appropriate pointer
        while (a_max < b_min && (a_idx + register_size) < a_length) {
            a_idx += register_size;
            a_progress = svwhilelt_b16_u64(a_idx, a_length);
            a_vec = svld1_u16(a_progress, a + a_idx);
            a_max = svlastb(a_progress, a_vec);
        }
        a_min = svlasta(svpfalse_b(), a_vec);
        while (b_max < a_min && (b_idx + register_size) < b_length) {
            b_idx += register_size;
            b_progress = svwhilelt_b16_u64(b_idx, b_length);
            b_vec = svld1_u16(b_progress, b + b_idx);
            b_max = svlastb(b_progress, b_vec);
        }
        b_min = svlasta(svpfalse_b(), b_vec);

        // Before we evaluate the intersection size, obfurscating the order in `b_vec`,
        // let's estimate how much we will need to advance the pointers afterwards.
        // For that, we don't even need to broadcast the values in SVE, as the whole
        // register can be compared against a scalar:
        //
        //      svuint16_t a_last_broadcasted =  svdup_n_u16(a_max);
        //      svuint16_t b_last_broadcasted =  svdup_n_u16(b_max);
        svbool_t a_mask = svcmple_n_u16(a_progress, a_vec, b_max);
        svbool_t b_mask = svcmple_n_u16(b_progress, b_vec, a_max);
        simsimd_u64_t a_step = svcntp_b16(a_progress, a_mask);
        simsimd_u64_t b_step = svcntp_b16(b_progress, b_mask);

        // Compare `a_vec` with each lane of `b_vec`
        svbfloat16_t a_weights_vec = svld1_bf16(a_progress, (__bf16 const *)a_weights + a_idx);
        svbfloat16_t b_weights_vec = svld1_bf16(b_progress, (__bf16 const *)b_weights + b_idx);
        for (simsimd_size_t i = 0; i < lanes_count; i++) {
            svbool_t equal_mask = svmatch_u16(a_progress, a_vec, b_vec);
            //! The `svsel_bf16` intrinsic is broken in many compilers, not returning the correct type.
            //! So we reinterprete floats as integers and apply `svsel_s16`, but the `svreinterpret_s16_bs16`
            //! and `svreinterpret_bf16_s16` are not always properly defined!
            svint16_t b_equal_weights_vec =
                svsel_s16(equal_mask, svreinterpret_s16_bf16(b_weights_vec), svdup_n_s16(0));
            product_vec = svbfdot_f32(product_vec, a_weights_vec, svreinterpret_bf16_s16(b_equal_weights_vec));
            b_vec = svext_u16(b_vec, b_vec, 8);
            intersection_size += svcntp_b16(svptrue_b16(), equal_mask);
        }

        // Advance
        a_idx += a_step;
        b_idx += b_step;
    }
    results[0] = (simsimd_distance_t)intersection_size;
    results[1] = svaddv_f32(svptrue_b32(), product_vec);
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SVE2 && SIMSIMD_TARGET_SVE_BF16
#endif // _SIMSIMD_TARGET_ARM

#ifdef __cplusplus
}
#endif

#endif
