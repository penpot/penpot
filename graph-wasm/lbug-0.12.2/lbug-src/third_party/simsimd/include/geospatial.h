/**
 *  @file       geospatial.h
 *  @brief      SIMD-accelerated Geo-Spatial distance functions.
 *  @author     Ash Vardanian
 *  @date       July 1, 2023
 *
 *  Contains:
 *  - Haversine (Great Circle) distance
 *  - Vincenty's distance function for Oblate Spheroid Geodesics
 *
 *  For datatypes:
 *  - 32-bit IEEE-754 floating point
 *  - 64-bit IEEE-754 floating point
 *
 *  For hardware architectures:
 *  - Arm: NEON
 *  - x86: Haswell
 *
 *  In most cases, for distance computations, we don't need the exact Haversine formula.
 *  The very last part of the computation applies `asin(sqrt(x))` non-linear transformation.
 *  Both `asin` and `sqrt` are monotonically increasing functions, so their product is also
 *  monotonically increasing. This means, for relative similarity/closeness computation we
 *  can avoid that expensive last step.
 *
 *  x86 intrinsics: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
 *  Arm intrinsics: https://developer.arm.com/architectures/instruction-sets/intrinsics/
 *  Oblate Spheroid Geodesic: https://mathworld.wolfram.com/OblateSpheroidGeodesic.html
 *  Staging experiments: https://github.com/ashvardanian/HaversineSimSIMD
 */
#ifndef SIMSIMD_GEOSPATIAL_H
#define SIMSIMD_GEOSPATIAL_H

#include "types.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifdef __cplusplus
}
#endif

#endif
