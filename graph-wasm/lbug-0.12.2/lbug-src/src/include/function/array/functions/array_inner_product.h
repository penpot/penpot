#pragma once

#include "common/vector/value_vector.h"
#include <simsimd.h>

namespace lbug {
namespace function {

struct ArrayInnerProduct {
    template<std::floating_point T>
    static inline void operation(common::list_entry_t& left, common::list_entry_t& right, T& result,
        common::ValueVector& leftVector, common::ValueVector& rightVector,
        common::ValueVector& /*resultVector*/) {
        auto leftElements = (T*)common::ListVector::getListValues(&leftVector, left);
        auto rightElements = (T*)common::ListVector::getListValues(&rightVector, right);
        KU_ASSERT(left.size == right.size);
        simsimd_distance_t tmpResult = 0.0;
        static_assert(std::is_same_v<T, float> || std::is_same_v<T, double>);
        if constexpr (std::is_same_v<T, float>) {
            simsimd_dot_f32(leftElements, rightElements, left.size, &tmpResult);
        } else {
            simsimd_dot_f64(leftElements, rightElements, left.size, &tmpResult);
        }
        result = tmpResult;
    }
};

} // namespace function
} // namespace lbug
