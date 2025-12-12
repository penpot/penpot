#pragma once

#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct BaseMapExtract {
    static void operation(common::list_entry_t& resultEntry, common::ValueVector& resultVector,
        uint8_t* srcValues, common::ValueVector* srcVector, uint64_t numValuesToCopy) {
        resultEntry = common::ListVector::addList(&resultVector, numValuesToCopy);
        auto dstValues = common::ListVector::getListValues(&resultVector, resultEntry);
        auto dstDataVector = common::ListVector::getDataVector(&resultVector);
        for (auto i = 0u; i < numValuesToCopy; i++) {
            dstDataVector->copyFromVectorData(dstValues, srcVector, srcValues);
            dstValues += dstDataVector->getNumBytesPerValue();
            srcValues += srcVector->getNumBytesPerValue();
        }
    }
};

} // namespace function
} // namespace lbug
