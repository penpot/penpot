#ifndef ALP_STATE_HPP
#define ALP_STATE_HPP

#include "alp/common.hpp"
#include "alp/config.hpp"
#include "alp/constants.hpp"
#include <cstdint>
#include <vector>

namespace alp {
struct state {
	SCHEME   scheme {SCHEME::ALP};
	uint32_t vector_size {config::VECTOR_SIZE};
	uint32_t exceptions_count {0};
	size_t   sampled_values_n {0};

	// ALP
	uint16_t                         k_combinations {5};
	std::vector<std::pair<int, int>> best_k_combinations;
	uint8_t                          exp;
	uint8_t                          fac;
	bw_t                             bit_width;
	int64_t                          for_base;
};
} // namespace alp

#endif
