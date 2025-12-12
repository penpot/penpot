/**
 *  @file       mesh.h
 *  @brief      SIMD-accelerated similarity measures for meshes and rigid 3D bodies.
 *  @author     Ash Vardanian
 *  @date       June 19, 2024
 *
 *  Contains:
 *  - Root Mean Square Deviation (RMSD) for rigid body superposition
 *  - Kabsch algorithm for optimal rigid body superposition
 *
 *  For datatypes:
 *  - 64-bit IEEE-754 floating point
 *  - 32-bit IEEE-754 floating point
 *  - 16-bit IEEE-754 floating point
 *  - 16-bit brain-floating point
 *
 *  For hardware architectures:
 *  - Arm: Neon
 *  - x86: Genoa, Sapphire
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 */
#ifndef SIMSIMD_MESH_H
#define SIMSIMD_MESH_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

// clang-format off

/*  Serial backends for all numeric types.
 *  By default they use 32-bit arithmetic, unless the arguments themselves contain 64-bit floats.
 *  For double-precision computation check out the "*_accurate" variants of those "*_serial" functions.
 */
SIMSIMD_PUBLIC void simsimd_rmsd_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_f64_t* a_centroid, simsimd_f64_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_f64_serial(simsimd_f64_t const* a, simsimd_f64_t const* b, simsimd_size_t n, simsimd_f64_t* a_centroid, simsimd_f64_t* b_centroid, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_rmsd_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_f32_t* a_centroid, simsimd_f32_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_f32_serial(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_f32_t* a_centroid, simsimd_f32_t* b_centroid, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_rmsd_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_f16_t* a_centroid, simsimd_f16_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_f16_serial(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_f16_t* a_centroid, simsimd_f16_t* b_centroid, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_rmsd_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_bf16_t* a_centroid, simsimd_bf16_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_bf16_serial(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_bf16_t* a_centroid, simsimd_bf16_t* b_centroid, simsimd_distance_t* result);

/*  Double-precision serial backends for all numeric types.
 *  For single-precision computation check out the "*_serial" counterparts of those "*_accurate" functions.
 */
SIMSIMD_PUBLIC void simsimd_rmsd_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_f32_t* a_centroid, simsimd_f32_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_f32_accurate(simsimd_f32_t const* a, simsimd_f32_t const* b, simsimd_size_t n, simsimd_f32_t* a_centroid, simsimd_f32_t* b_centroid, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_rmsd_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_f16_t* a_centroid, simsimd_f16_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_f16_accurate(simsimd_f16_t const* a, simsimd_f16_t const* b, simsimd_size_t n, simsimd_f16_t* a_centroid, simsimd_f16_t* b_centroid, simsimd_distance_t* result);

SIMSIMD_PUBLIC void simsimd_rmsd_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_bf16_t* a_centroid, simsimd_bf16_t* b_centroid, simsimd_distance_t* result);
SIMSIMD_PUBLIC void simsimd_kabsch_bf16_accurate(simsimd_bf16_t const* a, simsimd_bf16_t const* b, simsimd_size_t n, simsimd_bf16_t* a_centroid, simsimd_bf16_t* b_centroid, simsimd_distance_t* result);

// clang-format on

#ifdef __cplusplus
}
#endif

#endif
