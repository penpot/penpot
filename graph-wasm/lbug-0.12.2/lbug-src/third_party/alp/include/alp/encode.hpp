#ifndef ALP_ENCODE_HPP
#define ALP_ENCODE_HPP

#include "alp/config.hpp"
#include "alp/constants.hpp"
#include "alp/decode.hpp"
#include "alp/sampler.hpp"
#include "alp/state.hpp"
#include "common.hpp"
#include <cassert>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <map>
#include <utility>
#include <vector>

#ifdef __AVX2__

#include <immintrin.h>

#endif

/*
 * ALP Encoding
 */
namespace alp {

template <class T>
struct AlpEncode {

	using EXACT_TYPE                            = typename FloatingToExact<T>::type;
	using ENCODED_TYPE                          = FloatingToEncodedType<T>;
	static constexpr uint8_t EXACT_TYPE_BITSIZE = sizeof(EXACT_TYPE) * 8;

	/*
	 * Check for special values which are impossible for ALP to encode
	 * because they cannot be cast to int64 without an undefined behaviour
	 */
	static inline bool is_impossible_to_encode(const T n) {
		return !std::isfinite(n) || std::isnan(n) || n > ENCODING_UPPER_LIMIT || n < ENCODING_LOWER_LIMIT ||
		       (n == 0.0 && std::signbit(n)); //! Verification for -0.0
	}

	//! Scalar encoding a single value with ALP
	template <bool SAFE = true>
	static ENCODED_TYPE encode_value(const T value, const factor_idx_t factor_idx, const exponent_idx_t exponent_idx) {
		T tmp_encoded_value = value * Constants<T>::EXP_ARR[exponent_idx] * Constants<T>::FRAC_ARR[factor_idx];
		if constexpr (SAFE) {
			if (is_impossible_to_encode(tmp_encoded_value)) { return static_cast<ENCODED_TYPE>(ENCODING_UPPER_LIMIT); }
		}
		tmp_encoded_value = tmp_encoded_value + Constants<T>::MAGIC_NUMBER - Constants<T>::MAGIC_NUMBER;
		return static_cast<ENCODED_TYPE>(tmp_encoded_value);
	}

	//! Analyze FFOR to obtain bitwidth and frame-of-reference value
	static inline void analyze_ffor(const int64_t* input_vector, bw_t& bit_width, int64_t* base_for) {
		auto min = std::numeric_limits<int64_t>::max();
		auto max = std::numeric_limits<int64_t>::min();

		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			if (input_vector[i] < min) { min = input_vector[i]; }
			if (input_vector[i] > max) { max = input_vector[i]; }
		}

		const auto delta                    = (static_cast<uint64_t>(max) - static_cast<uint64_t>(min));
		const auto estimated_bits_per_value = static_cast<bw_t>(ceil(log2(delta + 1)));
		bit_width                           = estimated_bits_per_value;
		base_for[0]                         = min;
	}

	/*
	 * Function to sort the best combinations from each vector sampled from the rowgroup
	 * First criteria is number of times it appears
	 * Second criteria is bigger exponent
	 * Third criteria is bigger factor
	 */
	static inline bool compare_best_combinations(const std::pair<std::pair<int, int>, int>& t1,
	                                             const std::pair<std::pair<int, int>, int>& t2) {
		return (t1.second > t2.second) || (t1.second == t2.second && (t2.first.first < t1.first.first)) ||
		       ((t1.second == t2.second && t2.first.first == t1.first.first) && (t2.first.second < t1.first.second));
	}

	/*
	 * Find the best combinations of factor-exponent from each vector sampled from a rowgroup
	 * This function is called once per rowgroup
	 * This operates over ALP first level samples
	 */
	static inline void find_top_k_combinations(const T* smp_arr, state& stt) {
		const auto n_vectors_to_sample =
		    static_cast<uint64_t>(std::ceil(static_cast<double>(stt.sampled_values_n) / config::SAMPLES_PER_VECTOR));
		const uint64_t                     samples_size = std::min(stt.sampled_values_n, config::SAMPLES_PER_VECTOR);
		std::map<std::pair<int, int>, int> global_combinations;
		uint64_t                           smp_offset {0};

		// For each vector in the rg sample
		uint64_t best_estimated_compression_size {
		    (samples_size * (Constants<T>::EXCEPTION_SIZE + EXCEPTION_POSITION_SIZE)) +
		    (samples_size * (Constants<T>::EXCEPTION_SIZE))};
		for (size_t smp_n = 0; smp_n < n_vectors_to_sample; smp_n++) {
			uint8_t found_factor {0};
			uint8_t found_exponent {0};
			// We start our optimization with the worst possible total bits obtained from compression
			uint64_t sample_estimated_compression_size {
			    (samples_size * (Constants<T>::EXCEPTION_SIZE + EXCEPTION_POSITION_SIZE)) +
			    (samples_size * (Constants<T>::EXCEPTION_SIZE))}; // worst scenario

			// We try all combinations in search for the one which minimize the compression size
			for (int8_t exp_ref = Constants<T>::MAX_EXPONENT; exp_ref >= 0; exp_ref--) {
				for (int8_t factor_idx = exp_ref; factor_idx >= 0; factor_idx--) {
					uint32_t exceptions_count           = {0};
					uint32_t non_exceptions_count       = {0};
					uint32_t estimated_bits_per_value   = {0};
					uint64_t estimated_compression_size = {0};

					ENCODED_TYPE max_encoded_value = {std::numeric_limits<ENCODED_TYPE>::min()};
					ENCODED_TYPE min_encoded_value = {std::numeric_limits<ENCODED_TYPE>::max()};

					for (size_t i = 0; i < samples_size; i++) {
						const T            actual_value  = smp_arr[smp_offset + i];
						const ENCODED_TYPE encoded_value = encode_value(actual_value, factor_idx, exp_ref);
						const T decoded_value = AlpDecode<T>::decode_value(encoded_value, factor_idx, exp_ref);
						if (decoded_value == actual_value) {
							non_exceptions_count++;
							if (encoded_value > max_encoded_value) { max_encoded_value = encoded_value; }
							if (encoded_value < min_encoded_value) { min_encoded_value = encoded_value; }
						} else {
							exceptions_count++;
						}
					}

					// We do not take into account combinations which yield to almsot all exceptions
					if (non_exceptions_count < 2) { continue; }

					// Evaluate factor/exponent compression size (we optimize for FOR)
					const uint64_t delta =
					    (static_cast<uint64_t>(max_encoded_value) - static_cast<uint64_t>(min_encoded_value));
					estimated_bits_per_value = std::ceil(std::log2(delta + 1));
					estimated_compression_size += samples_size * estimated_bits_per_value;
					estimated_compression_size +=
					    exceptions_count * (Constants<T>::EXCEPTION_SIZE + EXCEPTION_POSITION_SIZE);

					if ((estimated_compression_size < sample_estimated_compression_size) ||
					    (estimated_compression_size == sample_estimated_compression_size &&
					     (found_exponent < exp_ref)) ||
					    // We prefer bigger exponents
					    ((estimated_compression_size == sample_estimated_compression_size &&
					      found_exponent == exp_ref) &&
					     (found_factor < factor_idx)) // We prefer bigger factors
					) {
						sample_estimated_compression_size = estimated_compression_size;
						found_exponent                    = exp_ref;
						found_factor                      = factor_idx;
						if (sample_estimated_compression_size < best_estimated_compression_size) {
							best_estimated_compression_size = sample_estimated_compression_size;
						}
					}
				}
			}
			std::pair<int, int> cmb = std::make_pair(found_exponent, found_factor);
			global_combinations[cmb]++;
			smp_offset += samples_size;
		}

		// We adapt scheme if we were not able to achieve compression in the current rg
		if (best_estimated_compression_size >= Constants<T>::RD_SIZE_THRESHOLD_LIMIT) {
			stt.scheme = SCHEME::ALP_RD;
			return;
		}

		// Convert our hash to a Combination vector to be able to sort
		// Note that this vector is always small (< 10 combinations)
		std::vector<std::pair<std::pair<int, int>, int>> best_k_combinations;
		best_k_combinations.reserve(global_combinations.size());
		for (auto const& itr : global_combinations) {
			best_k_combinations.emplace_back(itr.first, // Pair exp, fac
			                                 itr.second // N of times it appeared
			);
		}
		// We sort combinations based on times they appeared
		std::sort(best_k_combinations.begin(), best_k_combinations.end(), compare_best_combinations);
		if (best_k_combinations.size() < stt.k_combinations) { stt.k_combinations = best_k_combinations.size(); }

		// Save k' best exp, fac combination pairs
		for (size_t i {0}; i < stt.k_combinations; i++) {
			stt.best_k_combinations.push_back(best_k_combinations[i].first);
		}
	}

	/*
	 * Find the best combination of factor-exponent for a vector from within the best k combinations
	 * This is ALP second level sampling
	 */
	static inline void
	find_best_exponent_factor_from_combinations(const std::vector<std::pair<int, int>>& top_combinations,
	                                            const uint8_t                           top_k,
	                                            const T*                                input_vector,
	                                            const size_t                            input_vector_size,
	                                            uint8_t&                                factor,
	                                            uint8_t&                                exponent) {
		uint8_t  found_exponent {0};
		uint8_t  found_factor {0};
		uint64_t best_estimated_compression_size {0};
		uint8_t  worse_threshold_count {0};

		const int32_t sample_increments =
		    std::max(1, static_cast<int32_t>(std::ceil(input_vector_size / config::SAMPLES_PER_ROWGROUP)));

		// We try each K combination in search for the one which minimize the compression size in the vector
		for (size_t k {0}; k < top_k; k++) {
			const int    exp_idx    = top_combinations[k].first;
			const int    factor_idx = top_combinations[k].second;
			uint32_t     exception_count {0};
			uint32_t     estimated_bits_per_value {0};
			uint64_t     estimated_compression_size {0};
			ENCODED_TYPE max_encoded_value {std::numeric_limits<ENCODED_TYPE>::min()};
			ENCODED_TYPE min_encoded_value {std::numeric_limits<ENCODED_TYPE>::max()};

			for (size_t sample_idx = 0; sample_idx < input_vector_size; sample_idx += sample_increments) {
				const T            actual_value  = input_vector[sample_idx];
				const ENCODED_TYPE encoded_value = encode_value(actual_value, factor_idx, exp_idx);
				const T            decoded_value = AlpDecode<T>::decode_value(encoded_value, factor_idx, exp_idx);
				if (decoded_value == actual_value) {
					if (encoded_value > max_encoded_value) { max_encoded_value = encoded_value; }
					if (encoded_value < min_encoded_value) { min_encoded_value = encoded_value; }
				} else {
					exception_count++;
				}
			}

			// Evaluate factor/exponent performance (we optimize for FOR)
			const uint64_t delta     = max_encoded_value - min_encoded_value;
			estimated_bits_per_value = ceil(log2(delta + 1));
			estimated_compression_size += config::SAMPLES_PER_ROWGROUP * estimated_bits_per_value;
			estimated_compression_size += exception_count * (Constants<T>::EXCEPTION_SIZE + EXCEPTION_POSITION_SIZE);

			if (k == 0) { // First try with first combination
				best_estimated_compression_size = estimated_compression_size;
				found_factor                    = factor_idx;
				found_exponent                  = exp_idx;
				continue; // Go to second
			}
			if (estimated_compression_size >=
			    best_estimated_compression_size) { // If current is worse or equal than previous
				worse_threshold_count += 1;
				if (worse_threshold_count == SAMPLING_EARLY_EXIT_THRESHOLD) {
					break; // We stop only if two are worse
				}
				continue;
			}
			// Otherwise we replace best and continue with next
			best_estimated_compression_size = estimated_compression_size;
			found_factor                    = factor_idx;
			found_exponent                  = exp_idx;
			worse_threshold_count           = 0;
		}
		exponent = found_exponent;
		factor   = found_factor;
	}

	// DOUBLE
	static inline void encode_simdized(const double*        input_vector,
	                                   double*              exceptions,
	                                   exp_p_t*             exceptions_positions,
	                                   exp_c_t*             exceptions_count,
	                                   int64_t*             encoded_integers,
	                                   const factor_idx_t   factor_idx,
	                                   const exponent_idx_t exponent_idx) {
		alignas(64) static double   encoded_dbl_arr[1024];
		alignas(64) static double   dbl_arr_without_specials[1024];
		alignas(64) static uint64_t INDEX_ARR[1024];

		exp_p_t  current_exceptions_count {0};
		uint64_t exceptions_idx {0};

		// make copy of input with all special values replaced by  ENCODING_UPPER_LIMIT
		const auto* tmp_input = reinterpret_cast<const uint64_t*>(input_vector);
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			const auto is_special =
			    ((tmp_input[i] & 0x7FFFFFFFFFFFFFFF) >=
			     0x7FF0000000000000) // any NaN, +inf and -inf (https://stackoverflow.com/questions/29730530/)
			    || tmp_input[i] == Constants<double>::NEGATIVE_ZERO;

			if (is_special) {
				dbl_arr_without_specials[i] = ENCODING_UPPER_LIMIT;
			} else {
				dbl_arr_without_specials[i] = input_vector[i];
			}
		}

#pragma clang loop vectorize_width(64)
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			auto const actual_value = dbl_arr_without_specials[i];

			// Attempt conversion
			const int64_t encoded_value = encode_value<false>(actual_value, factor_idx, exponent_idx);
			encoded_integers[i]         = encoded_value;
			const double decoded_value  = AlpDecode<T>::decode_value(encoded_value, factor_idx, exponent_idx);
			encoded_dbl_arr[i]          = decoded_value;
		}

#ifdef __AVX512F__
		for (size_t i {0}; i < config::VECTOR_SIZE; i = i + 8) {
			__m512d l            = _mm512_loadu_pd(tmp_dbl_arr + i);
			__m512d r            = _mm512_loadu_pd(input_vector + i);
			__m512i index        = _mm512_loadu_pd(INDEX_ARR + i);
			auto    is_exception = _mm512_cmpneq_pd_mask(l, r);
			_mm512_mask_compressstoreu_pd(tmp_index + exceptions_idx, is_exception, index);
			exceptions_idx += LOOKUP_TABLE[is_exception];
		}
#else
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			auto l                    = encoded_dbl_arr[i];
			auto r                    = dbl_arr_without_specials[i];
			auto is_exception         = (l != r);
			INDEX_ARR[exceptions_idx] = i;
			exceptions_idx += is_exception;
		}
#endif

		int64_t a_non_exception_value = 0;
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			if (i != INDEX_ARR[i]) {
				a_non_exception_value = encoded_integers[i];
				break;
			}
		}

		for (size_t j {0}; j < exceptions_idx; j++) {
			size_t     i                                   = INDEX_ARR[j];
			const auto actual_value                        = input_vector[i];
			encoded_integers[i]                            = a_non_exception_value;
			exceptions[current_exceptions_count]           = actual_value;
			exceptions_positions[current_exceptions_count] = i;
			current_exceptions_count                       = current_exceptions_count + 1;
		}

		*exceptions_count = current_exceptions_count;
	}

	// FLOAT
	static inline void encode_simdized(const float*         input_vector,
	                                   float*               exceptions,
	                                   exp_p_t*             exceptions_positions,
	                                   exp_c_t*             exceptions_count,
	                                   int64_t*             encoded_integers,
	                                   const factor_idx_t   factor_idx,
	                                   const exponent_idx_t exponent_idx) {
		alignas(64) static float    encoded_dbl_arr[1024];
		alignas(64) static float    dbl_arr_without_specials[1024];
		alignas(64) static uint64_t INDEX_ARR[1024];

		exp_p_t  current_exceptions_count {0};
		uint64_t exceptions_idx {0};

		// make copy of input with all special values replaced by  ENCODING_UPPER_LIMIT
		const auto* tmp_input = reinterpret_cast<const uint32_t*>(input_vector);
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			const auto is_special =
			    ((tmp_input[i] & 0x7FFFFFFF) >=
			     0x7F800000) // any NaN, +inf and -inf (https://stackoverflow.com/questions/29730530/)
			    || tmp_input[i] == Constants<float>::NEGATIVE_ZERO;

			if (is_special) {
				dbl_arr_without_specials[i] = ENCODING_UPPER_LIMIT;
			} else {
				dbl_arr_without_specials[i] = input_vector[i];
			}
		}

#pragma clang loop vectorize_width(64)
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			auto const actual_value = dbl_arr_without_specials[i];

			// Attempt conversion
			const int64_t encoded_value = encode_value<false>(actual_value, factor_idx, exponent_idx);
			encoded_integers[i]         = encoded_value;
			const float decoded_value   = AlpDecode<T>::decode_value(encoded_value, factor_idx, exponent_idx);
			encoded_dbl_arr[i]          = decoded_value;
		}

#ifdef __AVX512F__
		for (size_t i {0}; i < config::VECTOR_SIZE; i = i + 16) {
			__m512  l            = _mm512_loadu_ps(tmp_dbl_arr + i);
			__m512  r            = _mm512_loadu_ps(input_vector + i);
			__m512i index        = _mm512_loadu_ps(INDEX_ARR + i);
			auto    is_exception = _mm512_cmpneq_ps_mask(l, r);
			_mm512_mask_compressstoreu_ps(tmp_index + exceptions_idx, is_exception, index);
			exceptions_idx += LOOKUP_TABLE[is_exception];
		}
#else
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			auto l                    = encoded_dbl_arr[i];
			auto r                    = dbl_arr_without_specials[i];
			auto is_exception         = (l != r);
			INDEX_ARR[exceptions_idx] = i;
			exceptions_idx += is_exception;
		}
#endif

		int64_t a_non_exception_value = 0;
		for (size_t i {0}; i < config::VECTOR_SIZE; i++) {
			if (i != INDEX_ARR[i]) {
				a_non_exception_value = encoded_integers[i];
				break;
			}
		}

		for (size_t j {0}; j < exceptions_idx; j++) {
			size_t     i                                   = INDEX_ARR[j];
			const auto actual_value                        = input_vector[i];
			encoded_integers[i]                            = a_non_exception_value;
			exceptions[current_exceptions_count]           = actual_value;
			exceptions_positions[current_exceptions_count] = i;
			current_exceptions_count                       = current_exceptions_count + 1;
		}

		*exceptions_count = current_exceptions_count;
	}

	static inline void encode(const T*  input_vector,
	                          T*        exceptions,
	                          uint32_t* exceptions_positions,
	                          uint32_t* exceptions_count,
	                          int64_t*  encoded_integers,
	                          state&    stt) {

		if (stt.k_combinations > 1) { // Only if more than 1 found top combinations we sample and search
			find_best_exponent_factor_from_combinations(
			    stt.best_k_combinations, stt.k_combinations, input_vector, stt.vector_size, stt.fac, stt.exp);
		} else {
			stt.exp = stt.best_k_combinations[0].first;
			stt.fac = stt.best_k_combinations[0].second;
		}
		encode_simdized(
		    input_vector, exceptions, exceptions_positions, exceptions_count, encoded_integers, stt.fac, stt.exp);
	}

	static inline void
	init(const T* data_column, const size_t column_offset, const size_t tuples_count, T* sample_arr, state& stt) {
		stt.scheme           = SCHEME::ALP;
		stt.sampled_values_n = sampler::first_level_sample<T>(data_column, column_offset, tuples_count, sample_arr);
		stt.k_combinations   = config::MAX_K_COMBINATIONS;
		stt.best_k_combinations.clear();
		find_top_k_combinations(sample_arr, stt);
	}
};

} // namespace alp
#endif
