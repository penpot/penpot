#pragma once

#include "base_count.h"

namespace lbug {
namespace function {

struct CountStarFunction : public BaseCountFunction {
    static constexpr const char* name = "COUNT_STAR";

    static void updateAll(uint8_t* state_, common::ValueVector* input, uint64_t multiplicity,
        common::InMemOverflowBuffer* /*overflowBuffer*/);

    static void updatePos(uint8_t* state_, common::ValueVector* input, uint64_t multiplicity,
        uint32_t /*pos*/, common::InMemOverflowBuffer* /*overflowBuffer*/);

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
