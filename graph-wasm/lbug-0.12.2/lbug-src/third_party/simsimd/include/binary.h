/**
 *  @file       binary.h
 *  @brief      SIMD-accelerated Binary Similarity Measures.
 *  @author     Ash Vardanian
 *  @date       July 1, 2023
 *
 *  Contains:
 *  - Hamming distance
 *  - Jaccard similarity (Tanimoto coefficient)
 *
 *  For hardware architectures:
 *  - Arm: NEON, SVE
 *  - x86: Haswell, Ice Lake
 *
 *  The hardest part of optimizing binary similarity measures is the population count operation.
 *  It's natively supported by almost every insrtuction set, but the throughput and latency can
 *  be suboptimal. There are several ways to optimize this operation:
 *
 *  - Lookup tables, mostly using nibbles (4-bit lookups)
 *  - Harley-Seal population counts: https://arxiv.org/pdf/1611.07612
 *
 *  On binary vectors, when computing Jaccard similarity we can clearly see how the CPU struggles
 *  to compute that many population counts. There are several instructions we should keep in mind
 *  for future optimizations:
 *
 *  - `_mm512_popcnt_epi64` maps to `VPOPCNTQ (ZMM, K, ZMM)`:
 *      - On Ice Lake: 3 cycles latency, ports: 1*p5
 *      - On Genoa: 2 cycles latency, ports: 1*FP01
 *  - `_mm512_shuffle_epi8` maps to `VPSHUFB (ZMM, ZMM, ZMM)`:
 *      - On Ice Lake: 1 cycles latency, ports: 1*p5
 *      - On Genoa: 2 cycles latency, ports: 1*FP12
 *  - `_mm512_sad_epu8` maps to `VPSADBW (ZMM, ZMM, ZMM)`:
 *      - On Ice Lake: 3 cycles latency, ports: 1*p5
 *      - On Zen4: 3 cycles latency, ports: 1*FP01
 *  - `_mm512_tertiarylogic_epi64` maps to `VPTERNLOGQ (ZMM, ZMM, ZMM, I8)`:
 *      - On Ice Lake: 1 cycles latency, ports: 1*p05
 *      - On Zen4: 1 cycles latency, ports: 1*FP0123
 *  - `_mm512_gf2p8mul_epi8` maps to `VPGF2P8AFFINEQB (ZMM, ZMM, ZMM)`:
 *      - On Ice Lake: 5 cycles latency, ports: 1*p0
 *      - On Zen4: 3 cycles latency, ports: 1*FP01
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 *  SSE POPCOUNT experiments by Wojciech Muła: https://github.com/WojciechMula/sse-popcount
 *  R&D progress tracker: https://github.com/ashvardanian/SimSIMD/pull/138
 */
#ifndef SIMSIMD_BINARY_H
#define SIMSIMD_BINARY_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

// clang-format off

/*  Serial backends for bitsets. */
SIMSIMD_PUBLIC void simsimd_hamming_b8_serial(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);
SIMSIMD_PUBLIC void simsimd_jaccard_b8_serial(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);

/*  Arm NEON backend for bitsets. */
SIMSIMD_PUBLIC void simsimd_hamming_b8_neon(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);
SIMSIMD_PUBLIC void simsimd_jaccard_b8_neon(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);

/*  Arm SVE backend for bitsets. */
SIMSIMD_PUBLIC void simsimd_hamming_b8_sve(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);
SIMSIMD_PUBLIC void simsimd_jaccard_b8_sve(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);

/*  x86 AVX2 backend for bitsets for Intel Haswell CPUs and newer, needs only POPCNT extensions. */
SIMSIMD_PUBLIC void simsimd_hamming_b8_haswell(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);
SIMSIMD_PUBLIC void simsimd_jaccard_b8_haswell(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);

/*  x86 AVX512 backend for bitsets for Intel Ice Lake CPUs and newer, using VPOPCNTDQ extensions. */
SIMSIMD_PUBLIC void simsimd_hamming_b8_ice(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);
SIMSIMD_PUBLIC void simsimd_jaccard_b8_ice(simsimd_b8_t const* a, simsimd_b8_t const* b, simsimd_size_t n_words, simsimd_distance_t* distance);
// clang-format on

SIMSIMD_PUBLIC unsigned char simsimd_popcount_b8(simsimd_b8_t x) {
    static unsigned char lookup_table[] = {
        0, 1, 1, 2, 1, 2, 2, 3, 1, 2, 2, 3, 2, 3, 3, 4, 1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5, //
        1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5, 2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
        1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5, 2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
        2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6, 3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
        1, 2, 2, 3, 2, 3, 3, 4, 2, 3, 3, 4, 3, 4, 4, 5, 2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6,
        2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6, 3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
        2, 3, 3, 4, 3, 4, 4, 5, 3, 4, 4, 5, 4, 5, 5, 6, 3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7,
        3, 4, 4, 5, 4, 5, 5, 6, 4, 5, 5, 6, 5, 6, 6, 7, 4, 5, 5, 6, 5, 6, 6, 7, 5, 6, 6, 7, 6, 7, 7, 8};
    return lookup_table[x];
}

SIMSIMD_PUBLIC void simsimd_hamming_b8_serial(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                              simsimd_distance_t *result) {
    simsimd_i32_t differences = 0;
    for (simsimd_size_t i = 0; i != n_words; ++i) differences += simsimd_popcount_b8(a[i] ^ b[i]);
    *result = differences;
}

SIMSIMD_PUBLIC void simsimd_jaccard_b8_serial(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                              simsimd_distance_t *result) {
    simsimd_i32_t intersection = 0, union_ = 0;
    for (simsimd_size_t i = 0; i != n_words; ++i)
        intersection += simsimd_popcount_b8(a[i] & b[i]), union_ += simsimd_popcount_b8(a[i] | b[i]);
    *result = (union_ != 0) ? 1 - (simsimd_f64_t)intersection / (simsimd_f64_t)union_ : 1;
}

#if _SIMSIMD_TARGET_ARM
#if SIMSIMD_TARGET_NEON
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+simd")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+simd"))), apply_to = function)

SIMSIMD_INTERNAL simsimd_u32_t _simsimd_reduce_u8x16_neon(uint8x16_t vec) {
    // Split the vector into two halves and widen to `uint16x8_t`
    uint16x8_t low_half = vmovl_u8(vget_low_u8(vec));   // widen lower 8 elements
    uint16x8_t high_half = vmovl_u8(vget_high_u8(vec)); // widen upper 8 elements

    // Sum the widened halves
    uint16x8_t sum16 = vaddq_u16(low_half, high_half);

    // Now reduce the `uint16x8_t` to a single `simsimd_u32_t`
    uint32x4_t sum32 = vpaddlq_u16(sum16);       // pairwise add into 32-bit integers
    uint64x2_t sum64 = vpaddlq_u32(sum32);       // pairwise add into 64-bit integers
    simsimd_u32_t final_sum = vaddvq_u64(sum64); // final horizontal add to 32-bit result
    return final_sum;
}

SIMSIMD_PUBLIC void simsimd_hamming_b8_neon(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                            simsimd_distance_t *result) {
    simsimd_i32_t differences = 0;
    simsimd_size_t i = 0;
    // In each 8-bit word we may have up to 8 differences.
    // So for up-to 31 cycles (31 * 16 = 496 word-dimensions = 3968 bits)
    // we can aggregate the differences into a `uint8x16_t` vector,
    // where each component will be up-to 255.
    while (i + 16 <= n_words) {
        uint8x16_t differences_cycle_vec = vdupq_n_u8(0);
        for (simsimd_size_t cycle = 0; cycle < 31 && i + 16 <= n_words; ++cycle, i += 16) {
            uint8x16_t a_vec = vld1q_u8(a + i);
            uint8x16_t b_vec = vld1q_u8(b + i);
            uint8x16_t xor_count_vec = vcntq_u8(veorq_u8(a_vec, b_vec));
            differences_cycle_vec = vaddq_u8(differences_cycle_vec, xor_count_vec);
        }
        differences += _simsimd_reduce_u8x16_neon(differences_cycle_vec);
    }
    // Handle the tail
    for (; i != n_words; ++i) differences += simsimd_popcount_b8(a[i] ^ b[i]);
    *result = differences;
}

SIMSIMD_PUBLIC void simsimd_jaccard_b8_neon(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                            simsimd_distance_t *result) {
    simsimd_i32_t intersection = 0, union_ = 0;
    simsimd_size_t i = 0;
    // In each 8-bit word we may have up to 8 intersections/unions.
    // So for up-to 31 cycles (31 * 16 = 496 word-dimensions = 3968 bits)
    // we can aggregate the intersections/unions into a `uint8x16_t` vector,
    // where each component will be up-to 255.
    while (i + 16 <= n_words) {
        uint8x16_t intersections_cycle_vec = vdupq_n_u8(0);
        uint8x16_t unions_cycle_vec = vdupq_n_u8(0);
        for (simsimd_size_t cycle = 0; cycle < 31 && i + 16 <= n_words; ++cycle, i += 16) {
            uint8x16_t a_vec = vld1q_u8(a + i);
            uint8x16_t b_vec = vld1q_u8(b + i);
            uint8x16_t and_count_vec = vcntq_u8(vandq_u8(a_vec, b_vec));
            uint8x16_t or_count_vec = vcntq_u8(vorrq_u8(a_vec, b_vec));
            intersections_cycle_vec = vaddq_u8(intersections_cycle_vec, and_count_vec);
            unions_cycle_vec = vaddq_u8(unions_cycle_vec, or_count_vec);
        }
        intersection += _simsimd_reduce_u8x16_neon(intersections_cycle_vec);
        union_ += _simsimd_reduce_u8x16_neon(unions_cycle_vec);
    }
    // Handle the tail
    for (; i != n_words; ++i)
        intersection += simsimd_popcount_b8(a[i] & b[i]), union_ += simsimd_popcount_b8(a[i] | b[i]);
    *result = (union_ != 0) ? 1 - (simsimd_f64_t)intersection / (simsimd_f64_t)union_ : 1;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_NEON

#if SIMSIMD_TARGET_SVE
#pragma GCC push_options
#pragma GCC target("arch=armv8.2-a+sve")
#pragma clang attribute push(__attribute__((target("arch=armv8.2-a+sve"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_hamming_b8_sve(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                           simsimd_distance_t *result) {

    // On very small register sizes, NEON is at least as fast as SVE.
    simsimd_size_t const words_per_register = svcntb();
    if (words_per_register <= 32) {
        simsimd_hamming_b8_neon(a, b, n_words, result);
        return;
    }

    // On larger register sizes, SVE is faster.
    simsimd_size_t i = 0, cycle = 0;
    simsimd_i32_t differences = 0;
    svuint8_t differences_cycle_vec = svdup_n_u8(0);
    svbool_t const all_vec = svptrue_b8();
    while (i < n_words) {
        do {
            svbool_t pg_vec = svwhilelt_b8((unsigned int)i, (unsigned int)n_words);
            svuint8_t a_vec = svld1_u8(pg_vec, a + i);
            svuint8_t b_vec = svld1_u8(pg_vec, b + i);
            differences_cycle_vec =
                svadd_u8_z(all_vec, differences_cycle_vec, svcnt_u8_x(all_vec, sveor_u8_m(all_vec, a_vec, b_vec)));
            i += words_per_register;
            ++cycle;
        } while (i < n_words && cycle < 31);
        differences += svaddv_u8(all_vec, differences_cycle_vec);
        differences_cycle_vec = svdup_n_u8(0);
        cycle = 0; // Reset the cycle counter.
    }

    *result = differences;
}

SIMSIMD_PUBLIC void simsimd_jaccard_b8_sve(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                           simsimd_distance_t *result) {

    // On very small register sizes, NEON is at least as fast as SVE.
    simsimd_size_t const words_per_register = svcntb();
    if (words_per_register <= 32) {
        simsimd_jaccard_b8_neon(a, b, n_words, result);
        return;
    }

    // On larger register sizes, SVE is faster.
    simsimd_size_t i = 0, cycle = 0;
    simsimd_i32_t intersection = 0, union_ = 0;
    svuint8_t intersection_cycle_vec = svdup_n_u8(0);
    svuint8_t union_cycle_vec = svdup_n_u8(0);
    svbool_t const all_vec = svptrue_b8();
    while (i < n_words) {
        do {
            svbool_t pg_vec = svwhilelt_b8((unsigned int)i, (unsigned int)n_words);
            svuint8_t a_vec = svld1_u8(pg_vec, a + i);
            svuint8_t b_vec = svld1_u8(pg_vec, b + i);
            intersection_cycle_vec =
                svadd_u8_z(all_vec, intersection_cycle_vec, svcnt_u8_x(all_vec, svand_u8_m(all_vec, a_vec, b_vec)));
            union_cycle_vec =
                svadd_u8_z(all_vec, union_cycle_vec, svcnt_u8_x(all_vec, svorr_u8_m(all_vec, a_vec, b_vec)));
            i += words_per_register;
            ++cycle;
        } while (i < n_words && cycle < 31);
        intersection += svaddv_u8(all_vec, intersection_cycle_vec);
        intersection_cycle_vec = svdup_n_u8(0);
        union_ += svaddv_u8(all_vec, union_cycle_vec);
        union_cycle_vec = svdup_n_u8(0);
        cycle = 0; // Reset the cycle counter.
    }

    *result = (union_ != 0) ? 1 - (simsimd_f64_t)intersection / (simsimd_f64_t)union_ : 1;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_SVE
#endif // _SIMSIMD_TARGET_ARM

#if _SIMSIMD_TARGET_X86
#if SIMSIMD_TARGET_ICE
#pragma GCC push_options
#pragma GCC target("avx2", "avx512f", "avx512vl", "bmi2", "avx512bw", "avx512vpopcntdq")
#pragma clang attribute push(__attribute__((target("avx2,avx512f,avx512vl,bmi2,avx512bw,avx512vpopcntdq"))), \
                             apply_to = function)

SIMSIMD_PUBLIC void simsimd_hamming_b8_ice(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                           simsimd_distance_t *result) {

    simsimd_size_t xor_count;
    // It's harder to squeeze out performance from tiny representations, so we unroll the loops for binary metrics.
    if (n_words <= 64) { // Up to 512 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words);
        __m512i a_vec = _mm512_maskz_loadu_epi8(mask, a);
        __m512i b_vec = _mm512_maskz_loadu_epi8(mask, b);
        __m512i xor_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a_vec, b_vec));
        xor_count = _mm512_reduce_add_epi64(xor_count_vec);
    }
    else if (n_words <= 128) { // Up to 1024 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words - 64);
        __m512i a1_vec = _mm512_loadu_epi8(a);
        __m512i b1_vec = _mm512_loadu_epi8(b);
        __m512i a2_vec = _mm512_maskz_loadu_epi8(mask, a + 64);
        __m512i b2_vec = _mm512_maskz_loadu_epi8(mask, b + 64);
        __m512i xor1_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a1_vec, b1_vec));
        __m512i xor2_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a2_vec, b2_vec));
        xor_count = _mm512_reduce_add_epi64(_mm512_add_epi64(xor2_count_vec, xor1_count_vec));
    }
    else if (n_words <= 196) { // Up to 1568 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words - 128);
        __m512i a1_vec = _mm512_loadu_epi8(a);
        __m512i b1_vec = _mm512_loadu_epi8(b);
        __m512i a2_vec = _mm512_loadu_epi8(a + 64);
        __m512i b2_vec = _mm512_loadu_epi8(b + 64);
        __m512i a3_vec = _mm512_maskz_loadu_epi8(mask, a + 128);
        __m512i b3_vec = _mm512_maskz_loadu_epi8(mask, b + 128);
        __m512i xor1_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a1_vec, b1_vec));
        __m512i xor2_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a2_vec, b2_vec));
        __m512i xor3_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a3_vec, b3_vec));
        xor_count =
            _mm512_reduce_add_epi64(_mm512_add_epi64(xor3_count_vec, _mm512_add_epi64(xor2_count_vec, xor1_count_vec)));
    }
    else if (n_words <= 256) { // Up to 2048 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words - 192);
        __m512i a1_vec = _mm512_loadu_epi8(a);
        __m512i b1_vec = _mm512_loadu_epi8(b);
        __m512i a2_vec = _mm512_loadu_epi8(a + 64);
        __m512i b2_vec = _mm512_loadu_epi8(b + 64);
        __m512i a3_vec = _mm512_loadu_epi8(a + 128);
        __m512i b3_vec = _mm512_loadu_epi8(b + 128);
        __m512i a4_vec = _mm512_maskz_loadu_epi8(mask, a + 192);
        __m512i b4_vec = _mm512_maskz_loadu_epi8(mask, b + 192);
        __m512i xor1_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a1_vec, b1_vec));
        __m512i xor2_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a2_vec, b2_vec));
        __m512i xor3_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a3_vec, b3_vec));
        __m512i xor4_count_vec = _mm512_popcnt_epi64(_mm512_xor_si512(a4_vec, b4_vec));
        xor_count = _mm512_reduce_add_epi64(_mm512_add_epi64(_mm512_add_epi64(xor4_count_vec, xor3_count_vec),
                                                             _mm512_add_epi64(xor2_count_vec, xor1_count_vec)));
    }
    else {
        __m512i xor_count_vec = _mm512_setzero_si512();
        __m512i a_vec, b_vec;

    simsimd_hamming_b8_ice_cycle:
        if (n_words < 64) {
            __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words);
            a_vec = _mm512_maskz_loadu_epi8(mask, a);
            b_vec = _mm512_maskz_loadu_epi8(mask, b);
            n_words = 0;
        }
        else {
            a_vec = _mm512_loadu_epi8(a);
            b_vec = _mm512_loadu_epi8(b);
            a += 64, b += 64, n_words -= 64;
        }
        __m512i xor_vec = _mm512_xor_si512(a_vec, b_vec);
        xor_count_vec = _mm512_add_epi64(xor_count_vec, _mm512_popcnt_epi64(xor_vec));
        if (n_words) goto simsimd_hamming_b8_ice_cycle;

        xor_count = _mm512_reduce_add_epi64(xor_count_vec);
    }
    *result = xor_count;
}

SIMSIMD_PUBLIC void simsimd_jaccard_b8_ice(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                           simsimd_distance_t *result) {

    simsimd_size_t intersection = 0, union_ = 0;
    //  It's harder to squeeze out performance from tiny representations, so we unroll the loops for binary metrics.
    if (n_words <= 64) { // Up to 512 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words);
        __m512i a_vec = _mm512_maskz_loadu_epi8(mask, a);
        __m512i b_vec = _mm512_maskz_loadu_epi8(mask, b);
        __m512i and_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a_vec, b_vec));
        __m512i or_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a_vec, b_vec));
        intersection = _mm512_reduce_add_epi64(and_count_vec);
        union_ = _mm512_reduce_add_epi64(or_count_vec);
    }
    else if (n_words <= 128) { // Up to 1024 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words - 64);
        __m512i a1_vec = _mm512_loadu_epi8(a);
        __m512i b1_vec = _mm512_loadu_epi8(b);
        __m512i a2_vec = _mm512_maskz_loadu_epi8(mask, a + 64);
        __m512i b2_vec = _mm512_maskz_loadu_epi8(mask, b + 64);
        __m512i and1_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a1_vec, b1_vec));
        __m512i or1_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a1_vec, b1_vec));
        __m512i and2_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a2_vec, b2_vec));
        __m512i or2_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a2_vec, b2_vec));
        intersection = _mm512_reduce_add_epi64(_mm512_add_epi64(and2_count_vec, and1_count_vec));
        union_ = _mm512_reduce_add_epi64(_mm512_add_epi64(or2_count_vec, or1_count_vec));
    }
    else if (n_words <= 196) { // Up to 1568 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words - 128);
        __m512i a1_vec = _mm512_loadu_epi8(a);
        __m512i b1_vec = _mm512_loadu_epi8(b);
        __m512i a2_vec = _mm512_loadu_epi8(a + 64);
        __m512i b2_vec = _mm512_loadu_epi8(b + 64);
        __m512i a3_vec = _mm512_maskz_loadu_epi8(mask, a + 128);
        __m512i b3_vec = _mm512_maskz_loadu_epi8(mask, b + 128);
        __m512i and1_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a1_vec, b1_vec));
        __m512i or1_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a1_vec, b1_vec));
        __m512i and2_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a2_vec, b2_vec));
        __m512i or2_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a2_vec, b2_vec));
        __m512i and3_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a3_vec, b3_vec));
        __m512i or3_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a3_vec, b3_vec));
        intersection = _mm512_reduce_add_epi64( //
            _mm512_add_epi64(and3_count_vec, _mm512_add_epi64(and2_count_vec, and1_count_vec)));
        union_ = _mm512_reduce_add_epi64( //
            _mm512_add_epi64(or3_count_vec, _mm512_add_epi64(or2_count_vec, or1_count_vec)));
    }
    else if (n_words <= 256) { // Up to 2048 bits.
        __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words - 192);
        __m512i a1_vec = _mm512_loadu_epi8(a);
        __m512i b1_vec = _mm512_loadu_epi8(b);
        __m512i a2_vec = _mm512_loadu_epi8(a + 64);
        __m512i b2_vec = _mm512_loadu_epi8(b + 64);
        __m512i a3_vec = _mm512_loadu_epi8(a + 128);
        __m512i b3_vec = _mm512_loadu_epi8(b + 128);
        __m512i a4_vec = _mm512_maskz_loadu_epi8(mask, a + 192);
        __m512i b4_vec = _mm512_maskz_loadu_epi8(mask, b + 192);
        __m512i and1_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a1_vec, b1_vec));
        __m512i or1_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a1_vec, b1_vec));
        __m512i and2_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a2_vec, b2_vec));
        __m512i or2_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a2_vec, b2_vec));
        __m512i and3_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a3_vec, b3_vec));
        __m512i or3_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a3_vec, b3_vec));
        __m512i and4_count_vec = _mm512_popcnt_epi64(_mm512_and_si512(a4_vec, b4_vec));
        __m512i or4_count_vec = _mm512_popcnt_epi64(_mm512_or_si512(a4_vec, b4_vec));
        intersection = _mm512_reduce_add_epi64(_mm512_add_epi64(_mm512_add_epi64(and4_count_vec, and3_count_vec),
                                                                _mm512_add_epi64(and2_count_vec, and1_count_vec)));
        union_ = _mm512_reduce_add_epi64(_mm512_add_epi64(_mm512_add_epi64(or4_count_vec, or3_count_vec),
                                                          _mm512_add_epi64(or2_count_vec, or1_count_vec)));
    }
    else {
        __m512i and_count_vec = _mm512_setzero_si512(), or_count_vec = _mm512_setzero_si512();
        __m512i a_vec, b_vec;

    simsimd_jaccard_b8_ice_cycle:
        if (n_words < 64) {
            __mmask64 mask = (__mmask64)_bzhi_u64(0xFFFFFFFFFFFFFFFF, n_words);
            a_vec = _mm512_maskz_loadu_epi8(mask, a);
            b_vec = _mm512_maskz_loadu_epi8(mask, b);
            n_words = 0;
        }
        else {
            a_vec = _mm512_loadu_epi8(a);
            b_vec = _mm512_loadu_epi8(b);
            a += 64, b += 64, n_words -= 64;
        }
        __m512i and_vec = _mm512_and_si512(a_vec, b_vec);
        __m512i or_vec = _mm512_or_si512(a_vec, b_vec);
        and_count_vec = _mm512_add_epi64(and_count_vec, _mm512_popcnt_epi64(and_vec));
        or_count_vec = _mm512_add_epi64(or_count_vec, _mm512_popcnt_epi64(or_vec));
        if (n_words) goto simsimd_jaccard_b8_ice_cycle;

        intersection = _mm512_reduce_add_epi64(and_count_vec);
        union_ = _mm512_reduce_add_epi64(or_count_vec);
    }
    *result = (union_ != 0) ? 1 - (simsimd_f64_t)intersection / (simsimd_f64_t)union_ : 1;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_ICE

#if SIMSIMD_TARGET_HASWELL
#pragma GCC push_options
#pragma GCC target("popcnt")
#pragma clang attribute push(__attribute__((target("popcnt"))), apply_to = function)

SIMSIMD_PUBLIC void simsimd_hamming_b8_haswell(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                               simsimd_distance_t *result) {
    // x86 supports unaligned loads and works just fine with the scalar version for small vectors.
    simsimd_size_t differences = 0;
    for (; n_words >= 8; n_words -= 8, a += 8, b += 8)
        differences += _mm_popcnt_u64(*(simsimd_u64_t const *)a ^ *(simsimd_u64_t const *)b);
    for (; n_words; --n_words, ++a, ++b) differences += _mm_popcnt_u32(*a ^ *b);
    *result = differences;
}

SIMSIMD_PUBLIC void simsimd_jaccard_b8_haswell(simsimd_b8_t const *a, simsimd_b8_t const *b, simsimd_size_t n_words,
                                               simsimd_distance_t *result) {
    // x86 supports unaligned loads and works just fine with the scalar version for small vectors.
    simsimd_size_t intersection = 0, union_ = 0;
    for (; n_words >= 8; n_words -= 8, a += 8, b += 8)
        intersection += _mm_popcnt_u64(*(simsimd_u64_t const *)a & *(simsimd_u64_t const *)b),
            union_ += _mm_popcnt_u64(*(simsimd_u64_t const *)a | *(simsimd_u64_t const *)b);
    for (; n_words; --n_words, ++a, ++b) intersection += _mm_popcnt_u32(*a & *b), union_ += _mm_popcnt_u32(*a | *b);
    *result = (union_ != 0) ? 1 - (simsimd_f64_t)intersection / (simsimd_f64_t)union_ : 1;
}

#pragma clang attribute pop
#pragma GCC pop_options
#endif // SIMSIMD_TARGET_HASWELL
#endif // _SIMSIMD_TARGET_X86

#ifdef __cplusplus
}
#endif

#endif
