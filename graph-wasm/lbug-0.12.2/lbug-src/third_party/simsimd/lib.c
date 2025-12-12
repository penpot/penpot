/**
 *  @brief  Dynamic dispatch library for SimSIMD.
 *  @note   Compile with the most recent compiler available.
 *  @file   lib.c
 */
#define SIMSIMD_DYNAMIC_DISPATCH 1
#define SIMSIMD_NATIVE_F16 0
#define SIMSIMD_NATIVE_BF16 0

/*  Depending on the Operating System, the following intrinsics are available
 *  on recent compiler toolchains:
 *
 *  - Linux: everything is available in GCC 12+ and Clang 16+.
 *  - Windows - MSVC: everything except Sapphire Rapids and ARM SVE.
 *  - MacOS - Apple Clang: only Arm NEON and x86 AVX2 Haswell extensions are available.
 */
#if !defined(SIMSIMD_TARGET_NEON) && (defined(__APPLE__) || defined(__linux__))
#define SIMSIMD_TARGET_NEON 1
#endif
#if !defined(SIMSIMD_TARGET_SVE) && (defined(__linux__))
#define SIMSIMD_TARGET_SVE 1
#endif
#if !defined(SIMSIMD_TARGET_SVE2) && (defined(__linux__))
#define SIMSIMD_TARGET_SVE2 1
#endif
#if !defined(SIMSIMD_TARGET_HASWELL) && (defined(_MSC_VER) || defined(__APPLE__) || defined(__linux__))
#define SIMSIMD_TARGET_HASWELL 1
#endif
#if !defined(SIMSIMD_TARGET_SKYLAKE) && (defined(_MSC_VER) || defined(__linux__))
#define SIMSIMD_TARGET_SKYLAKE 1
#endif
#if !defined(SIMSIMD_TARGET_ICE) && (defined(_MSC_VER) || defined(__linux__))
#define SIMSIMD_TARGET_ICE 1
#endif
#if !defined(SIMSIMD_TARGET_GENOA) && (defined(__linux__))
#define SIMSIMD_TARGET_GENOA 1
#endif
#if !defined(SIMSIMD_TARGET_SAPPHIRE) && (defined(__linux__))
#define SIMSIMD_TARGET_SAPPHIRE 1
#endif
#if !defined(SIMSIMD_TARGET_TURIN) && (defined(__linux__))
#define SIMSIMD_TARGET_TURIN 1
#endif
#if !defined(SIMSIMD_TARGET_SIERRA) && (defined(__linux__)) && 0 // TODO: Add target spec to GCC & Clang
#define SIMSIMD_TARGET_SIERRA 1
#endif

#include <simsimd.h>

#ifdef __cplusplus
extern "C" {
#endif

// Every time a function is called, it checks if the metric is already loaded. If not, it fetches it.
// If no metric is found, it returns NaN. We can obtain NaN by dividing 0.0 by 0.0, but that annoys
// the MSVC compiler. Instead we can directly write-in the signaling NaN (0x7FF0000000000001)
// or the qNaN (0x7FF8000000000000).
#define SIMSIMD_DECLARATION_DENSE(name, extension)                                                                    \
    SIMSIMD_DYNAMIC void simsimd_##name##_##extension(simsimd_##extension##_t const *a,                               \
                                                      simsimd_##extension##_t const *b, simsimd_size_t n,             \
                                                      simsimd_distance_t *results) {                                  \
        static simsimd_metric_dense_punned_t metric = 0;                                                              \
        if (metric == 0) {                                                                                            \
            simsimd_capability_t used_capability;                                                                     \
            simsimd_find_kernel_punned(simsimd_metric_##name##_k, simsimd_datatype_##extension##_k,                   \
                                       simsimd_capabilities(), simsimd_cap_any_k, (simsimd_kernel_punned_t *)&metric, \
                                       &used_capability);                                                             \
            if (!metric) {                                                                                            \
                *(simsimd_u64_t *)results = 0x7FF0000000000001ull;                                                    \
                return;                                                                                               \
            }                                                                                                         \
        }                                                                                                             \
        metric(a, b, n, results);                                                                                     \
    }

#define SIMSIMD_DECLARATION_SPARSE(name, extension, type)                                                       \
    SIMSIMD_DYNAMIC void simsimd_##name##_##extension(simsimd_##type##_t const *a, simsimd_##type##_t const *b, \
                                                      simsimd_size_t a_length, simsimd_size_t b_length,         \
                                                      simsimd_distance_t *result) {                             \
        static simsimd_metric_sparse_punned_t metric = 0;                                                       \
        if (metric == 0) {                                                                                      \
            simsimd_capability_t used_capability;                                                               \
            simsimd_find_kernel_punned(simsimd_metric_##name##_k, simsimd_datatype_##extension##_k,             \
                                       simsimd_capabilities(), simsimd_cap_any_k,                               \
                                       (simsimd_kernel_punned_t *)(&metric), &used_capability);                 \
            if (!metric) {                                                                                      \
                *(simsimd_u64_t *)result = 0x7FF0000000000001ull;                                               \
                return;                                                                                         \
            }                                                                                                   \
        }                                                                                                       \
        metric(a, b, a_length, b_length, result);                                                               \
    }

#define SIMSIMD_DECLARATION_CURVED(name, extension)                                                           \
    SIMSIMD_DYNAMIC void simsimd_##name##_##extension(                                                        \
        simsimd_##extension##_t const *a, simsimd_##extension##_t const *b, simsimd_##extension##_t const *c, \
        simsimd_size_t n, simsimd_distance_t *result) {                                                       \
        static simsimd_metric_curved_punned_t metric = 0;                                                     \
        if (metric == 0) {                                                                                    \
            simsimd_capability_t used_capability;                                                             \
            simsimd_find_kernel_punned(simsimd_metric_##name##_k, simsimd_datatype_##extension##_k,           \
                                       simsimd_capabilities(), simsimd_cap_any_k,                             \
                                       (simsimd_kernel_punned_t *)(&metric), &used_capability);               \
            if (!metric) {                                                                                    \
                *(simsimd_u64_t *)result = 0x7FF0000000000001ull;                                             \
                return;                                                                                       \
            }                                                                                                 \
        }                                                                                                     \
        metric(a, b, c, n, result);                                                                           \
    }

#define SIMSIMD_DECLARATION_FMA(name, extension)                                                                \
    SIMSIMD_DYNAMIC void simsimd_##name##_##extension(                                                          \
        simsimd_##extension##_t const *a, simsimd_##extension##_t const *b, simsimd_##extension##_t const *c,   \
        simsimd_size_t n, simsimd_distance_t alpha, simsimd_distance_t beta, simsimd_##extension##_t *result) { \
        static simsimd_kernel_fma_punned_t metric = 0;                                                          \
        if (metric == 0) {                                                                                      \
            simsimd_capability_t used_capability;                                                               \
            simsimd_find_kernel_punned(simsimd_metric_##name##_k, simsimd_datatype_##extension##_k,             \
                                       simsimd_capabilities(), simsimd_cap_any_k,                               \
                                       (simsimd_kernel_punned_t *)(&metric), &used_capability);                 \
        }                                                                                                       \
        metric(a, b, c, n, alpha, beta, result);                                                                \
    }

#define SIMSIMD_DECLARATION_WSUM(name, extension)                                                   \
    SIMSIMD_DYNAMIC void simsimd_##name##_##extension(                                              \
        simsimd_##extension##_t const *a, simsimd_##extension##_t const *b, simsimd_size_t n,       \
        simsimd_distance_t alpha, simsimd_distance_t beta, simsimd_##extension##_t *result) {       \
        static simsimd_kernel_wsum_punned_t metric = 0;                                             \
        if (metric == 0) {                                                                          \
            simsimd_capability_t used_capability;                                                   \
            simsimd_find_kernel_punned(simsimd_metric_##name##_k, simsimd_datatype_##extension##_k, \
                                       simsimd_capabilities(), simsimd_cap_any_k,                   \
                                       (simsimd_kernel_punned_t *)(&metric), &used_capability);     \
        }                                                                                           \
        metric(a, b, n, alpha, beta, result);                                                       \
    }

// Dot products
SIMSIMD_DECLARATION_DENSE(dot, i8)
SIMSIMD_DECLARATION_DENSE(dot, u8)
SIMSIMD_DECLARATION_DENSE(dot, f16)
SIMSIMD_DECLARATION_DENSE(dot, bf16)
SIMSIMD_DECLARATION_DENSE(dot, f32)
SIMSIMD_DECLARATION_DENSE(dot, f64)
SIMSIMD_DECLARATION_DENSE(dot, f16c)
SIMSIMD_DECLARATION_DENSE(dot, bf16c)
SIMSIMD_DECLARATION_DENSE(dot, f32c)
SIMSIMD_DECLARATION_DENSE(dot, f64c)
SIMSIMD_DECLARATION_DENSE(vdot, f16c)
SIMSIMD_DECLARATION_DENSE(vdot, bf16c)
SIMSIMD_DECLARATION_DENSE(vdot, f32c)
SIMSIMD_DECLARATION_DENSE(vdot, f64c)

// Spatial distances
SIMSIMD_DECLARATION_DENSE(cos, i8)
SIMSIMD_DECLARATION_DENSE(cos, u8)
SIMSIMD_DECLARATION_DENSE(cos, f16)
SIMSIMD_DECLARATION_DENSE(cos, bf16)
SIMSIMD_DECLARATION_DENSE(cos, f32)
SIMSIMD_DECLARATION_DENSE(cos, f64)
SIMSIMD_DECLARATION_DENSE(l2sq, i8)
SIMSIMD_DECLARATION_DENSE(l2sq, u8)
SIMSIMD_DECLARATION_DENSE(l2sq, f16)
SIMSIMD_DECLARATION_DENSE(l2sq, bf16)
SIMSIMD_DECLARATION_DENSE(l2sq, f32)
SIMSIMD_DECLARATION_DENSE(l2sq, f64)
SIMSIMD_DECLARATION_DENSE(l2, i8)
SIMSIMD_DECLARATION_DENSE(l2, u8)
SIMSIMD_DECLARATION_DENSE(l2, f16)
SIMSIMD_DECLARATION_DENSE(l2, bf16)
SIMSIMD_DECLARATION_DENSE(l2, f32)
SIMSIMD_DECLARATION_DENSE(l2, f64)

// Binary distances
SIMSIMD_DECLARATION_DENSE(hamming, b8)
SIMSIMD_DECLARATION_DENSE(jaccard, b8)

// Probability distributions
SIMSIMD_DECLARATION_DENSE(kl, f16)
SIMSIMD_DECLARATION_DENSE(kl, bf16)
SIMSIMD_DECLARATION_DENSE(kl, f32)
SIMSIMD_DECLARATION_DENSE(kl, f64)
SIMSIMD_DECLARATION_DENSE(js, f16)
SIMSIMD_DECLARATION_DENSE(js, bf16)
SIMSIMD_DECLARATION_DENSE(js, f32)
SIMSIMD_DECLARATION_DENSE(js, f64)

// Sparse sets
SIMSIMD_DECLARATION_SPARSE(intersect, u16, u16)
SIMSIMD_DECLARATION_SPARSE(intersect, u32, u32)

// Curved spaces
SIMSIMD_DECLARATION_CURVED(bilinear, f64)
SIMSIMD_DECLARATION_CURVED(bilinear, f64c)
SIMSIMD_DECLARATION_CURVED(mahalanobis, f64)
SIMSIMD_DECLARATION_CURVED(bilinear, f32)
SIMSIMD_DECLARATION_CURVED(bilinear, f32c)
SIMSIMD_DECLARATION_CURVED(mahalanobis, f32)
SIMSIMD_DECLARATION_CURVED(bilinear, f16)
SIMSIMD_DECLARATION_CURVED(bilinear, f16c)
SIMSIMD_DECLARATION_CURVED(mahalanobis, f16)
SIMSIMD_DECLARATION_CURVED(bilinear, bf16)
SIMSIMD_DECLARATION_CURVED(bilinear, bf16c)
SIMSIMD_DECLARATION_CURVED(mahalanobis, bf16)

// Element-wise operations
SIMSIMD_DECLARATION_FMA(fma, f64)
SIMSIMD_DECLARATION_FMA(fma, f32)
SIMSIMD_DECLARATION_FMA(fma, f16)
SIMSIMD_DECLARATION_FMA(fma, bf16)
SIMSIMD_DECLARATION_FMA(fma, i8)
SIMSIMD_DECLARATION_FMA(fma, u8)
SIMSIMD_DECLARATION_WSUM(wsum, f64)
SIMSIMD_DECLARATION_WSUM(wsum, f32)
SIMSIMD_DECLARATION_WSUM(wsum, f16)
SIMSIMD_DECLARATION_WSUM(wsum, bf16)
SIMSIMD_DECLARATION_WSUM(wsum, i8)
SIMSIMD_DECLARATION_WSUM(wsum, u8)

SIMSIMD_DYNAMIC int simsimd_uses_neon(void) { return (simsimd_capabilities() & simsimd_cap_neon_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_neon_f16(void) { return (simsimd_capabilities() & simsimd_cap_neon_f16_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_neon_bf16(void) { return (simsimd_capabilities() & simsimd_cap_neon_bf16_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_neon_i8(void) { return (simsimd_capabilities() & simsimd_cap_neon_i8_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_sve(void) { return (simsimd_capabilities() & simsimd_cap_sve_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_sve_f16(void) { return (simsimd_capabilities() & simsimd_cap_sve_f16_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_sve_bf16(void) { return (simsimd_capabilities() & simsimd_cap_sve_bf16_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_sve_i8(void) { return (simsimd_capabilities() & simsimd_cap_sve_i8_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_haswell(void) { return (simsimd_capabilities() & simsimd_cap_haswell_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_skylake(void) { return (simsimd_capabilities() & simsimd_cap_skylake_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_ice(void) { return (simsimd_capabilities() & simsimd_cap_ice_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_genoa(void) { return (simsimd_capabilities() & simsimd_cap_genoa_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_sapphire(void) { return (simsimd_capabilities() & simsimd_cap_sapphire_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_turin(void) { return (simsimd_capabilities() & simsimd_cap_turin_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_sierra(void) { return (simsimd_capabilities() & simsimd_cap_sierra_k) != 0; }
SIMSIMD_DYNAMIC int simsimd_uses_dynamic_dispatch(void) { return 1; }
//SIMSIMD_DYNAMIC int simsimd_flush_denormals(void) { return _simsimd_flush_denormals(); }

SIMSIMD_DYNAMIC simsimd_capability_t simsimd_capabilities(void) {
    //! The latency of the CPUID instruction can be over 100 cycles, so we cache the result.
    static simsimd_capability_t static_capabilities = simsimd_cap_any_k;
    if (static_capabilities != simsimd_cap_any_k) return static_capabilities;

    static_capabilities = _simsimd_capabilities_implementation();

    // In multithreaded applications we need to ensure that the function pointers are pre-initialized,
    // so the first time we are probing for capabilities, we should also probe all of our metrics
    // with dummy inputs:
    simsimd_distance_t dummy_results_buffer[2];
    simsimd_distance_t *dummy_results = &dummy_results_buffer[0];
    void *x = 0;

    // Dense:
    simsimd_dot_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, dummy_results);
    simsimd_dot_u8((simsimd_u8_t *)x, (simsimd_u8_t *)x, 0, dummy_results);
    simsimd_dot_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_dot_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_dot_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_dot_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);

    simsimd_dot_f16c((simsimd_f16c_t *)x, (simsimd_f16c_t *)x, 0, dummy_results);
    simsimd_dot_bf16c((simsimd_bf16c_t *)x, (simsimd_bf16c_t *)x, 0, dummy_results);
    simsimd_dot_f32c((simsimd_f32c_t *)x, (simsimd_f32c_t *)x, 0, dummy_results);
    simsimd_dot_f64c((simsimd_f64c_t *)x, (simsimd_f64c_t *)x, 0, dummy_results);
    simsimd_vdot_f16c((simsimd_f16c_t *)x, (simsimd_f16c_t *)x, 0, dummy_results);
    simsimd_vdot_bf16c((simsimd_bf16c_t *)x, (simsimd_bf16c_t *)x, 0, dummy_results);
    simsimd_vdot_f32c((simsimd_f32c_t *)x, (simsimd_f32c_t *)x, 0, dummy_results);
    simsimd_vdot_f64c((simsimd_f64c_t *)x, (simsimd_f64c_t *)x, 0, dummy_results);

    simsimd_cos_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, dummy_results);
    simsimd_cos_u8((simsimd_u8_t *)x, (simsimd_u8_t *)x, 0, dummy_results);
    simsimd_cos_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_cos_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_cos_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_cos_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);

    simsimd_l2sq_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, dummy_results);
    simsimd_l2sq_u8((simsimd_u8_t *)x, (simsimd_u8_t *)x, 0, dummy_results);
    simsimd_l2sq_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_l2sq_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_l2sq_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_l2sq_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);

    simsimd_l2_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, dummy_results);
    simsimd_l2_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, dummy_results);
    simsimd_l2_u8((simsimd_u8_t *)x, (simsimd_u8_t *)x, 0, dummy_results);
    simsimd_l2_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_l2_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_l2_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_l2_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);

    simsimd_hamming_b8((simsimd_b8_t *)x, (simsimd_b8_t *)x, 0, dummy_results);
    simsimd_jaccard_b8((simsimd_b8_t *)x, (simsimd_b8_t *)x, 0, dummy_results);

    simsimd_kl_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_kl_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_kl_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_kl_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);
    simsimd_js_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_js_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_js_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_js_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);

    // Sparse
    simsimd_intersect_u16((simsimd_u16_t *)x, (simsimd_u16_t *)x, 0, 0, dummy_results);
    simsimd_intersect_u32((simsimd_u32_t *)x, (simsimd_u32_t *)x, 0, 0, dummy_results);

    // Curved:
    simsimd_bilinear_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);
    simsimd_mahalanobis_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, dummy_results);
    simsimd_bilinear_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_mahalanobis_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, dummy_results);
    simsimd_bilinear_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_mahalanobis_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, dummy_results);
    simsimd_bilinear_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);
    simsimd_mahalanobis_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, dummy_results);

    // Elementwise
    simsimd_wsum_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, 0, 0, (simsimd_f64_t *)x);
    simsimd_wsum_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, 0, 0, (simsimd_f32_t *)x);
    simsimd_wsum_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, 0, 0, (simsimd_f16_t *)x);
    simsimd_wsum_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, 0, 0, (simsimd_bf16_t *)x);
    simsimd_wsum_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, 0, 0, (simsimd_i8_t *)x);
    simsimd_wsum_u8((simsimd_u8_t *)x, (simsimd_u8_t *)x, 0, 0, 0, (simsimd_u8_t *)x);
    simsimd_fma_f64((simsimd_f64_t *)x, (simsimd_f64_t *)x, (simsimd_f64_t *)x, 0, 0, 0, (simsimd_f64_t *)x);
    simsimd_fma_f32((simsimd_f32_t *)x, (simsimd_f32_t *)x, (simsimd_f32_t *)x, 0, 0, 0, (simsimd_f32_t *)x);
    simsimd_fma_f16((simsimd_f16_t *)x, (simsimd_f16_t *)x, (simsimd_f16_t *)x, 0, 0, 0, (simsimd_f16_t *)x);
    simsimd_fma_bf16((simsimd_bf16_t *)x, (simsimd_bf16_t *)x, (simsimd_bf16_t *)x, 0, 0, 0, (simsimd_bf16_t *)x);
    simsimd_fma_i8((simsimd_i8_t *)x, (simsimd_i8_t *)x, (simsimd_i8_t *)x, 0, 0, 0, (simsimd_i8_t *)x);
    simsimd_fma_u8((simsimd_u8_t *)x, (simsimd_u8_t *)x, (simsimd_u8_t *)x, 0, 0, 0, (simsimd_u8_t *)x);

    return static_capabilities;
}

SIMSIMD_DYNAMIC void simsimd_find_kernel_punned( //
    simsimd_metric_kind_t kind,                  //
    simsimd_datatype_t datatype,                 //
    simsimd_capability_t supported,              //
    simsimd_capability_t allowed,                //
    simsimd_kernel_punned_t *kernel_output,      //
    simsimd_capability_t *capability_output) {
    _simsimd_find_kernel_punned_implementation(kind, datatype, supported, allowed, kernel_output, capability_output);
}

#ifdef __cplusplus
}
#endif
