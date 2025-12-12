#pragma once

#include "base_list_sort_function.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

template<typename T>
struct ListSort : BaseListSortOperation {
    static void operation(common::list_entry_t& input, common::list_entry_t& result,
        common::ValueVector& inputVector, common::ValueVector& resultVector) {
        sortValues<T>(input, result, inputVector, resultVector, true /* ascOrder */,
            true /* nullFirst */);
    }

    static void operation(common::list_entry_t& input, common::ku_string_t& sortOrder,
        common::list_entry_t& result, common::ValueVector& inputVector,
        common::ValueVector& /*valueVector*/, common::ValueVector& resultVector) {
        sortValues<T>(input, result, inputVector, resultVector, isAscOrder(sortOrder.getAsString()),
            true /* nullFirst */);
    }

    static void operation(common::list_entry_t& input, common::ku_string_t& sortOrder,
        common::ku_string_t& nullOrder, common::list_entry_t& result,
        common::ValueVector& inputVector, common::ValueVector& resultVector) {
        sortValues<T>(input, result, inputVector, resultVector, isAscOrder(sortOrder.getAsString()),
            isNullFirst(nullOrder.getAsString()));
    }
};

} // namespace function
} // namespace lbug
