#ifndef ALP_DECODE_HPP
#define ALP_DECODE_HPP

#include "common.hpp"
#include "constants.hpp"
#include <cstdint>

namespace alp {

#ifdef AVX2
#include "immintrin.h"

// from: https://stackoverflow.com/questions/41144668/how-to-efficiently-perform-double-int64-conversions-with-sse-avx
//  Only works for inputs in the range: [-2^51, 2^51]
__m128i double_to_int64(__m128d x) {
	x = _mm_add_pd(x, _mm_set1_pd(0x0018000000000000));
	return _mm_sub_epi64(_mm_castpd_si128(x), _mm_castpd_si128(_mm_set1_pd(0x0018000000000000)));
}

//  Only works for inputs in the range: [-2^51, 2^51]
__m128d int64_to_double(__m128i x) {
	x = _mm_add_epi64(x, _mm_castpd_si128(_mm_set1_pd(0x0018000000000000)));
	return _mm_sub_pd(_mm_castsi128_pd(x), _mm_set1_pd(0x0018000000000000));
}

/*
 * scalar version of int64_to_double
 */
double int64_to_double(int64_t x) {
	double magic_number = static_cast<double>(0x0018000000000000);
	x                   = x + static_cast<int64_t>(magic_number);
	return static_cast<double>(x) - static_cast<double>(magic_number);
}

// SSE version of int64_to_double
// Only works for inputs in the range: [-2^51, 2^51]
__m128d sse_int64_to_double(__m128i x) {
	x = _mm_add_epi64(x, _mm_castpd_si128(_mm_set1_pd(0x0018000000000000)));
	return _mm_sub_pd(_mm_castsi128_pd(x), _mm_set1_pd(0x0018000000000000));
}

__m256d int64_to_double_fast_precise(const __m256i v)
/* Optimized full range int64_t to double conversion           */
/* Emulate _mm256_cvtepi64_pd()                                */
{
	__m256i magic_i_lo   = _mm256_set1_epi64x(0x4330000000000000); /* 2^52               encoded as floating-point  */
	__m256i magic_i_hi32 = _mm256_set1_epi64x(0x4530000080000000); /* 2^84 + 2^63        encoded as floating-point  */
	__m256i magic_i_all  = _mm256_set1_epi64x(0x4530000080100000); /* 2^84 + 2^63 + 2^52 encoded as floating-point  */
	__m256d magic_d_all  = _mm256_castsi256_pd(magic_i_all);

	__m256i v_lo =
	    _mm256_blend_epi32(magic_i_lo, v, 0b01010101); /* Blend the 32 lowest significant bits of v with magic_int_lo */
	__m256i v_hi     = _mm256_srli_epi64(v, 32);       /* Extract the 32 most significant bits of v       */
	v_hi             = _mm256_xor_si256(v_hi, magic_i_hi32); /* Flip the msb of v_hi and blend with 0x45300000 */
	__m256d v_hi_dbl = _mm256_sub_pd(_mm256_castsi256_pd(v_hi), magic_d_all); /* Compute in double precision: */
	__m256d result   = _mm256_add_pd(
        v_hi_dbl,
        _mm256_castsi256_pd(
            v_lo)); /* (v_hi - magic_d_all) + v_lo  Do not assume associativity of floating point addition !! */
	return result;    /* With gcc use -O3, then -fno-associative-math is default. Do not use -Ofast, which enables
	             -fassociative-math! */
}

void sse_decode(const int64_t* digits, uint8_t fac_idx, uint8_t exp_idx, double* out_p) {
	uint64_t factor     = alp::U_FACT_ARR[fac_idx];
	double   frac10     = alp::Constants<double>::FRAC_ARR[exp_idx];
	__m128i  factor_sse = _mm_set1_epi64x(factor);
	__m128d  frac10_sse = _mm_set1_pd(frac10);

	auto digits_p = reinterpret_cast<const __m128i*>(digits);

	for (size_t i {0}; i < 512; ++i) {
		__m128i digit       = _mm_loadu_si128(digits_p + i);
		__m128i tmp_int     = digit * factor_sse;
		__m128d tmp_dbl     = sse_int64_to_double(tmp_int);
		__m128d tmp_dbl_mlt = tmp_dbl * frac10_sse;
		_mm_storeu_pd(out_p + (i * 2), tmp_dbl_mlt);
	}
}

void avx2_decode(const int64_t* digits, uint8_t fac_idx, uint8_t exp_idx, double* out_p) {
	uint64_t factor     = alp::U_FACT_ARR[fac_idx];
	double   frac10     = alp::Constants<double>::FRAC_ARR[exp_idx];
	__m256i  factor_sse = _mm256_set1_epi64x(factor);
	__m256d  frac10_sse = _mm256_set1_pd(frac10);

	auto digits_p = reinterpret_cast<const __m256i*>(digits);

	for (size_t i {0}; i < 256; ++i) {
		__m256i digit       = _mm256_loadu_si256(digits_p + i);
		__m256i tmp_int     = digit * factor_sse;
		__m256d tmp_dbl     = int64_to_double_fast_precise(tmp_int);
		__m256d tmp_dbl_mlt = tmp_dbl * frac10_sse;
		_mm256_storeu_pd(out_p + (i * 4), tmp_dbl_mlt);
	}
}

#endif

template <class T>
struct AlpDecode {

	//! Scalar decoding a single value with ALP
	static inline T decode_value(const int64_t encoded_value, const uint8_t factor, const uint8_t exponent) {
		const T decoded_value = encoded_value * FACT_ARR[factor] * alp::Constants<T>::FRAC_ARR[exponent];
		return decoded_value;
	}

	//! Scalar decoding of an ALP vector
	static inline void
	decode(const int64_t* encoded_integers, const uint8_t fac_idx, const uint8_t exp_idx, T* output) {
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			output[i] = decode_value(encoded_integers[i], fac_idx, exp_idx);
		}
	}

	//! Patch Exceptions
	static inline void patch_exceptions(T*             out,
	                                    const T*       exceptions,
	                                    const exp_p_t* exceptions_positions,
	                                    const exp_c_t* exceptions_count) {
		const auto exp_c = exceptions_count[0];
		for (exp_c_t i {0}; i < exp_c; i++) {
			out[exceptions_positions[i]] = exceptions[i];
		}
	}
};

} // namespace alp

#endif // ALP_DECODE_HPP
