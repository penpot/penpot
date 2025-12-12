#pragma once

#include "common/vector/value_vector.h"
#include "function/comparison/comparison_functions.h"

namespace lbug {
namespace function {

struct MapExtract {
    template<typename T>
    static void operation(common::list_entry_t& listEntry, T& key,
        common::list_entry_t& resultEntry, common::ValueVector& listVector,
        common::ValueVector& keyVector, common::ValueVector& resultVector) {
        auto mapKeyVector = common::MapVector::getKeyVector(&listVector);
        auto mapKeyValues = common::MapVector::getMapKeys(&listVector, listEntry);
        auto mapValVector = common::MapVector::getValueVector(&listVector);
        auto mapValPos = listEntry.offset;
        common::offset_vec_t mapValPoses;
        uint8_t comparisonResult = 0;
        for (auto i = 0u; i < listEntry.size; i++) {
            Equals::operation(*reinterpret_cast<T*>(mapKeyValues), key, comparisonResult,
                mapKeyVector, &keyVector);
            if (comparisonResult) {
                mapValPoses.push_back(mapValPos);
            }
            mapKeyValues += mapKeyVector->getNumBytesPerValue();
            mapValPos++;
        }
        resultEntry = common::ListVector::addList(&resultVector, mapValPoses.size());
        auto resultOffset = resultEntry.offset;
        for (auto& valPos : mapValPoses) {
            common::ListVector::getDataVector(&resultVector)
                ->copyFromVectorData(resultOffset++, mapValVector, valPos);
        }
    }
};

} // namespace function
} // namespace lbug
