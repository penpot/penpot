#pragma once

#include "common/exception/runtime.h"
#include "common/vector/value_vector.h"
#include "function/list/functions/list_unique_function.h"
#include "main/client_context.h"

namespace lbug {
namespace function {

static void duplicateValueHandler(const std::string& key) {
    throw common::RuntimeException{common::stringFormat("Found duplicate key: {} in map.", key)};
}

static void nullValueHandler() {
    throw common::RuntimeException("Null value key is not allowed in map.");
}

static void validateKeys(common::list_entry_t& keyEntry, common::ValueVector& keyVector) {
    ListUnique::appendListElementsToValueSet(keyEntry, keyVector, duplicateValueHandler,
        nullptr /* uniqueValueHandler */, nullValueHandler);
}

struct MapCreation {
    static void operation(common::list_entry_t& keyEntry, common::list_entry_t& valueEntry,
        common::list_entry_t& resultEntry, common::ValueVector& keyVector,
        common::ValueVector& valueVector, common::ValueVector& resultVector, void* dataPtr) {
        if (keyEntry.size != valueEntry.size) {
            throw common::RuntimeException{"Unaligned key list and value list."};
        }
        if (!reinterpret_cast<FunctionBindData*>(dataPtr)
                 ->clientContext->getClientConfig()
                 ->disableMapKeyCheck) {
            validateKeys(keyEntry, keyVector);
        }
        resultEntry = common::ListVector::addList(&resultVector, keyEntry.size);
        auto resultStructVector = common::ListVector::getDataVector(&resultVector);
        copyListEntry(resultEntry,
            common::StructVector::getFieldVector(resultStructVector, 0 /* keyVector */).get(),
            keyEntry, &keyVector);
        copyListEntry(resultEntry,
            common::StructVector::getFieldVector(resultStructVector, 1 /* valueVector */).get(),
            valueEntry, &valueVector);
    }

    static void copyListEntry(common::list_entry_t& resultEntry, common::ValueVector* resultVector,
        common::list_entry_t& srcEntry, common::ValueVector* srcVector) {
        auto resultPos = resultEntry.offset;
        auto srcDataVector = common::ListVector::getDataVector(srcVector);
        auto srcPos = srcEntry.offset;
        for (auto i = 0u; i < srcEntry.size; i++) {
            resultVector->copyFromVectorData(resultPos++, srcDataVector, srcPos++);
        }
    }
};

} // namespace function
} // namespace lbug
