#ifndef ALP_SAMPLER_HPP
#define ALP_SAMPLER_HPP

#include "alp/config.hpp"
#include <algorithm>
#include <cmath>

namespace alp::sampler {

template <class T>
inline size_t first_level_sample(const T* data, const size_t data_offset, const size_t data_size, T* data_sample) {
	const size_t left_in_data          = data_size - data_offset;
	const size_t portion_to_sample     = std::min(config::ROWGROUP_SIZE, left_in_data);
	const size_t available_alp_vectors = std::ceil(static_cast<double>(portion_to_sample) / config::VECTOR_SIZE);
	size_t       sample_idx            = 0;
	size_t       data_idx              = data_offset;

	for (size_t vector_idx = 0; vector_idx < available_alp_vectors; vector_idx++) {
		const size_t current_vector_n_values = std::min(data_size - data_idx, config::VECTOR_SIZE);

		//! We sample equidistant vectors; to do this we skip a fixed values of vectors
		//! If we are not in the correct jump, we do not take sample from this vector
		if (const bool must_select_rowgroup_sample = (vector_idx % config::ROWGROUP_SAMPLES_JUMP) == 0;
		    !must_select_rowgroup_sample) {
			data_idx += current_vector_n_values;
			continue;
		}

		const size_t n_sampled_increments = std::max(
		    1,
		    static_cast<int32_t>(std::ceil(static_cast<double>(current_vector_n_values) / config::SAMPLES_PER_VECTOR)));

		//! We do not take samples of non-complete duckdb vectors (usually the last one)
		//! Except in the case of too little data
		if (current_vector_n_values < config::SAMPLES_PER_VECTOR && sample_idx != 0) {
			data_idx += current_vector_n_values;
			continue;
		}

		// Storing the sample of that vector
		for (size_t i = 0; i < current_vector_n_values; i += n_sampled_increments) {
			data_sample[sample_idx] = data[data_idx + i];
			sample_idx++;
		}
		data_idx += current_vector_n_values;
	}
	return sample_idx;
}

} // namespace alp::sampler

#endif
