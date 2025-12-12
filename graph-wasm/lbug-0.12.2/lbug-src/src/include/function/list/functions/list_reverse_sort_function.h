#pragma once

#include "base_list_sort_function.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

template<typename T>
struct ListReverseSort : BaseListSortOperation {
    static inline void operation(common::list_entry_t& input, common::list_entry_t& result,
        common::ValueVector& inputVector, common::ValueVector& resultVector) {
        sortValues<T>(input, result, inputVector, resultVector, false /* ascOrder */,
            true /* nullFirst */);
    }

    static inline void operation(common::list_entry_t& input, common::ku_string_t& nullOrder,
        common::list_entry_t& result, common::ValueVector& inputVector,
        common::ValueVector& /*valueVector*/, common::ValueVector& resultVector) {
        sortValues<T>(input, result, inputVector, resultVector, false /* ascOrder */,
            isNullFirst(nullOrder.getAsString()) /* nullFirst */);
    }

    static inline void operation(common::list_entry_t& /*input*/,
        common::ku_string_t& /*sortOrder*/, common::ku_string_t& /*nullOrder*/,
        common::list_entry_t& /*result*/, common::ValueVector& /*inputVector*/,
        common::ValueVector& /*resultVector*/) {
        throw common::RuntimeException("Invalid number of arguments");
    }
};

} // namespace function
} // namespace lbug
