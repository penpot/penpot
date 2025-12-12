#pragma once

#include "common/vector/value_vector.h"
#include "function/map/functions/base_map_extract_function.h"

namespace lbug {
namespace function {

struct MapValues : public BaseMapExtract {
    static void operation(common::list_entry_t& listEntry, common::list_entry_t& resultEntry,
        common::ValueVector& listVector, common::ValueVector& resultVector) {
        auto mapValueVector = common::MapVector::getValueVector(&listVector);
        auto mapValueValues = common::MapVector::getMapValues(&listVector, listEntry);
        BaseMapExtract::operation(resultEntry, resultVector, mapValueValues, mapValueVector,
            listEntry.size);
    }
};

} // namespace function
} // namespace lbug
