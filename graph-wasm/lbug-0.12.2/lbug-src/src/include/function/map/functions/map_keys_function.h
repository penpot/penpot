#pragma once

#include "function/map/functions/base_map_extract_function.h"

namespace lbug {
namespace function {

struct MapKeys : public BaseMapExtract {
    static void operation(common::list_entry_t& listEntry, common::list_entry_t& resultEntry,
        common::ValueVector& listVector, common::ValueVector& resultVector) {
        auto mapKeyVector = common::MapVector::getKeyVector(&listVector);
        auto mapKeyValues = common::MapVector::getMapKeys(&listVector, listEntry);
        BaseMapExtract::operation(resultEntry, resultVector, mapKeyValues, mapKeyVector,
            listEntry.size);
    }
};

} // namespace function
} // namespace lbug
