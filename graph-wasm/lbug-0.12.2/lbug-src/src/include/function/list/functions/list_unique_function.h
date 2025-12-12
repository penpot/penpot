#pragma once

#include "common/type_utils.h"
#include "common/types/value/value.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct ValueHashFunction {
    uint64_t operator()(const common::Value& value) const { return (uint64_t)value.computeHash(); }
};

struct ValueEquality {
    bool operator()(const common::Value& a, const common::Value& b) const { return a == b; }
};

using ValueSet = std::unordered_set<common::Value, ValueHashFunction, ValueEquality>;

using duplicate_value_handler = std::function<void(const std::string&)>;
using unique_value_handler = std::function<void(common::ValueVector& dataVector, uint64_t pos)>;
using null_value_handler = std::function<void()>;

struct ListUnique {
    static uint64_t appendListElementsToValueSet(common::list_entry_t& input,
        common::ValueVector& inputVector, duplicate_value_handler duplicateValHandler = nullptr,
        unique_value_handler uniqueValueHandler = nullptr,
        null_value_handler nullValueHandler = nullptr);

    static void operation(common::list_entry_t& input, int64_t& result,
        common::ValueVector& inputVector, common::ValueVector& resultVector);
};

} // namespace function
} // namespace lbug
