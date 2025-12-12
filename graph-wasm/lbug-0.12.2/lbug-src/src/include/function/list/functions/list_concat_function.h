#pragma once

#include "common/types/types.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct ListConcat {
public:
    static void operation(common::list_entry_t& left, common::list_entry_t& right,
        common::list_entry_t& result, common::ValueVector& leftVector,
        common::ValueVector& rightVector, common::ValueVector& resultVector);
};

} // namespace function
} // namespace lbug
