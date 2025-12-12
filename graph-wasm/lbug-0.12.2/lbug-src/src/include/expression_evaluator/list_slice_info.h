#pragma once

#include "common/vector/value_vector.h"

namespace lbug::evaluator {

class LambdaParamEvaluator;

class ListEntryTracker {
public:
    explicit ListEntryTracker(common::ValueVector* listVector);

    common::offset_t getCurDataOffset() const { return getCurListEntry().offset + offsetInList; }
    common::offset_t getNextDataOffset();
    common::list_entry_t getCurListEntry() const {
        return listVector->getValue<common::list_entry_t>(getListEntryPos());
    }
    common::idx_t getListEntryPos() const { return listEntries[listEntryIdx]; }

    bool done() const { return listEntryIdx >= listEntries.size(); }

private:
    void updateListEntry();

    common::ValueVector* listVector;
    common::idx_t listEntryIdx;
    common::offset_t offsetInList;

    // selected pos of each list entry
    std::vector<common::sel_t> listEntries;
};

/**
 * List data vectors can their number of elements exceed DEFAULT_VECTOR_CAPACITY
 * However, most expression evaluators can only process elements in batches of size
 * DEFAULT_VECTOR_CAPACITY
 * This means that in order for lambda evaluators to work it must pass in its data in slices of size
 * DEFAULT_VECTOR_CAPACITY for processing by child evaluators
 *
 * A consequence of this is that some lists may have their data vectors split into different slices
 * and thus it is unreasonable to have execFuncs operate on a list-by-list basis. Instead, they
 * should operate on each data vector entry individually.
 *
 * Instead, any lambda execFunc should follow this pattern using the ListSliceInfo struct
 * void execFunc(...) {
 *   auto& sliceInfo = *listLambdaBindData->sliceInfo;
 *   // loop through each data vector entry in the slice
 *   for (sel_t i = 0; i < sliceInfo.getSliceSize(); ++i) {
 *       // dataOffset: the offset of the current entry in the input data vector
 *       // listEntryPos: the pos of the list entry containing to the data entry in the list vector
 *       const auto [listEntryPos, dataOffset] = sliceInfo.getPos(i);
 *       doSomething(listEntryPos, dataOffset);
 *   }
 *
 *   // do any final processing required on each list entry vector
 *   // only do this once for all slices
 *   if (sliceInfo.done()) {
 *       for (uint64_t i = 0; i < inputSelVector.getSelSize(); ++i) {
 *           auto pos = inputSelVector[i];
 *           doSomething(inputVector, pos);
 *       }
 *   }
 */
class ListSliceInfo {
public:
    explicit ListSliceInfo(common::ValueVector* listVector)
        : resultSliceOffset(0), listEntryTracker(listVector),
          sliceDataState(std::make_shared<common::DataChunkState>()),
          sliceListEntryState(std::make_shared<common::DataChunkState>()) {
        sliceDataState->setToUnflat();
        sliceDataState->getSelVectorUnsafe().setToFiltered();
        sliceListEntryState->setToUnflat();
        sliceListEntryState->getSelVectorUnsafe().setToFiltered();
    }

    void nextSlice();

    std::vector<std::shared_ptr<common::DataChunkState>> overrideAndSaveParamStates(
        std::span<LambdaParamEvaluator*> lambdaParamEvaluators);
    static void restoreParamStates(std::span<LambdaParamEvaluator*> lambdaParamEvaluators,
        std::vector<std::shared_ptr<common::DataChunkState>> savedStates);

    // use in cases (like list filter) where the output data offset may not correspond to the input
    // data offset
    common::offset_t& getResultSliceOffset() { return resultSliceOffset; }

    bool done() const;

    common::sel_t getSliceSize() const {
        KU_ASSERT(sliceDataState->getSelSize() == sliceListEntryState->getSelSize());
        return sliceDataState->getSelSize();
    }

    // returns {list entry pos, data pos}
    std::pair<common::sel_t, common::sel_t> getPos(common::idx_t i) const {
        return {sliceListEntryState->getSelVector()[i], sliceDataState->getSelVector()[i]};
    }

private:
    void updateSelVector();

    // offset/size refer to the data vector
    common::offset_t resultSliceOffset;

    ListEntryTracker listEntryTracker;

    std::shared_ptr<common::DataChunkState> sliceDataState;
    std::shared_ptr<common::DataChunkState> sliceListEntryState;
};

} // namespace lbug::evaluator
