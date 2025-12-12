#pragma once

#include "math.h"

#include "common/vector/value_vector.h"
#include "function/array/functions/array_squared_distance.h"

namespace lbug {
namespace function {

// Euclidean distance between two arrays.
struct ArrayDistance {
    template<std::floating_point T>
    static inline void operation(common::list_entry_t& left, common::list_entry_t& right, T& result,
        common::ValueVector& leftVector, common::ValueVector& rightVector,
        common::ValueVector& resultVector) {
        ArraySquaredDistance::operation(left, right, result, leftVector, rightVector, resultVector);
        result = std::sqrt(result);
    }
};

} // namespace function
} // namespace lbug
