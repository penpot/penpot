#pragma once

#include "common/vector/value_vector.h"
#include "function/comparison/comparison_functions.h"

namespace lbug {
namespace function {

struct ListPosition {
    // Note: this function takes in a 1-based element (The index of the first element in the list
    // is 1).
    template<typename T>
    static void operation(common::list_entry_t& list, T& element, int64_t& result,
        common::ValueVector& listVector, common::ValueVector& elementVector,
        common::ValueVector& /*resultVector*/) {
        if (common::ListType::getChildType(listVector.dataType) != elementVector.dataType) {
            result = 0;
            return;
        }
        auto listElements =
            reinterpret_cast<T*>(common::ListVector::getListValues(&listVector, list));
        uint8_t comparisonResult = 0;
        for (auto i = 0u; i < list.size; i++) {
            Equals::operation(listElements[i], element, comparisonResult,
                common::ListVector::getDataVector(&listVector), &elementVector);
            if (comparisonResult) {
                result = i + 1;
                return;
            }
        }
        result = 0;
    }
};

} // namespace function
} // namespace lbug
