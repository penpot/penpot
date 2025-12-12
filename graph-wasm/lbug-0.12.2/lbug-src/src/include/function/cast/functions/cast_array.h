#pragma once

#include "common/types/types.h"
#include "common/vector/value_vector.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct CastArrayHelper {
    static bool checkCompatibleNestedTypes(LogicalTypeID sourceTypeID, LogicalTypeID targetTypeID);

    static bool isUnionSpecialCast(const LogicalType& srcType, const LogicalType& dstType);

    static bool containsListToArray(const LogicalType& srcType, const LogicalType& dstType);

    static void validateListEntry(ValueVector* inputVector, const LogicalType& resultType,
        uint64_t pos);
};

} // namespace function
} // namespace lbug
