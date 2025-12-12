#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

template<typename T>
struct ArrayCrossProduct {
    static inline void operation(common::list_entry_t& left, common::list_entry_t& right,
        common::list_entry_t& result, common::ValueVector& leftVector,
        common::ValueVector& rightVector, common::ValueVector& resultVector) {
        auto leftElements = (T*)common::ListVector::getListValues(&leftVector, left);
        auto rightElements = (T*)common::ListVector::getListValues(&rightVector, right);
        result = common::ListVector::addList(&resultVector, left.size);
        auto resultElements = (T*)common::ListVector::getListValues(&resultVector, result);
        resultElements[0] = leftElements[1] * rightElements[2] - leftElements[2] * rightElements[1];
        resultElements[1] = leftElements[2] * rightElements[0] - leftElements[0] * rightElements[2];
        resultElements[2] = leftElements[0] * rightElements[1] - leftElements[1] * rightElements[0];
    }
};

} // namespace function
} // namespace lbug
