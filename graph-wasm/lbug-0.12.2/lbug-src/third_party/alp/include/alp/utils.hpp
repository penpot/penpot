#ifndef ALP_UTILS_HPP
#define ALP_UTILS_HPP

#include "alp/config.hpp"
#include "alp/encode.hpp"
#include <cmath>
#include <random>

namespace alp {

template <class T>
struct AlpApiUtils {

	static size_t get_rowgroup_count(size_t values_count) {
		return std::ceil((double)values_count / config::ROWGROUP_SIZE);
	};

	static size_t get_complete_vector_count(size_t n_values) {
		return std::floor(static_cast<double>(n_values) / config::VECTOR_SIZE);
	}

	/*
	 * Function to get the size of a vector after bit packing
	 * Note that we always store VECTOR_SIZE size vectors
	 */
	static size_t get_size_after_bitpacking(uint8_t bit_width) {
		return align_value<size_t, 8>(config::VECTOR_SIZE * bit_width) / 8;
	}

	template <class M, M val = 8>
	static M align_value(M n) {
		return ((n + (val - 1)) / val) * val;
	}

	static void fill_incomplete_alp_vector(T*        input_vector,
	                                       T*        exceptions,
	                                       uint32_t* exceptions_positions,
	                                       uint32_t* exceptions_count,
	                                       int64_t*  encoded_integers,
	                                       state&    stt) {

		static auto* tmp_index = new (std::align_val_t {64}) uint64_t[1024];

		// We fill a vector with 0s since these values will never be exceptions
		for (size_t i = stt.vector_size; i < config::VECTOR_SIZE; i++) {
			input_vector[i] = 0.0;
		}
		// We encode the vector filled with the dummy values
		AlpEncode<T>::encode(input_vector, exceptions, exceptions_positions, exceptions_count, encoded_integers, stt);
		T a_non_exception_value = 0.0;
		// We lookup the first non exception value from the true vector values
		for (size_t i {0}; i < stt.vector_size; i++) {
			if (i != tmp_index[i]) {
				a_non_exception_value = input_vector[i];
				break;
			}
		}
		// We fill the vector with this dummy value
		for (size_t i = stt.vector_size; i < config::VECTOR_SIZE; i++) {
			input_vector[i] = a_non_exception_value;
		}
	}

	static void fill_incomplete_alprd_vector(T* input_vector, const state& stt) {
		// We just fill the vector with the first value
		const T first_vector_value = input_vector[0];
		for (size_t i = stt.vector_size; i < config::VECTOR_SIZE; i++) {
			input_vector[i] = first_vector_value;
		}
	}
};
} // namespace alp

#endif
