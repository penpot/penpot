
#include "expression_evaluator/list_slice_info.h"

#include "common/system_config.h"
#include "expression_evaluator/lambda_evaluator.h"

namespace lbug {
namespace evaluator {

ListEntryTracker::ListEntryTracker(common::ValueVector* listVector)
    : listVector(listVector), listEntryIdx(0), offsetInList(0),
      listEntries(listVector->state->getSelSize()) {
    // it is not guaranteed that the list entries in a list vector are sorted by offset (the
    // case evaluator breaks this) so we need to sort it manually
    for (common::sel_t i = 0; i < listVector->state->getSelSize(); ++i) {
        listEntries[i] = listVector->state->getSelVector()[i];
    }
    std::sort(listEntries.begin(), listEntries.end(), [listVector](const auto a, const auto b) {
        return listVector->getValue<common::list_entry_t>(a).offset <
               listVector->getValue<common::list_entry_t>(b).offset;
    });
    updateListEntry();
}

common::offset_t ListEntryTracker::getNextDataOffset() {
    ++offsetInList;
    if (offsetInList >= getCurListEntry().size) {
        ++listEntryIdx;
        updateListEntry();
        offsetInList = 0;
        if (done()) {
            return common::INVALID_OFFSET;
        }
    }
    return getCurDataOffset();
}

void ListEntryTracker::updateListEntry() {
    while (true) {
        if (listEntryIdx >= listEntries.size()) {
            break;
        }
        const auto newEntry = listVector->getValue<common::list_entry_t>(getListEntryPos());
        if (!listVector->isNull(getListEntryPos()) && newEntry.size > 0) {
            break;
        }
        ++listEntryIdx;
    }
}

std::vector<std::shared_ptr<common::DataChunkState>> ListSliceInfo::overrideAndSaveParamStates(
    std::span<LambdaParamEvaluator*> lambdaParamEvaluators) {
    std::vector<std::shared_ptr<common::DataChunkState>> savedStates;

    // The sel states of the result vectors in evaluator trees often point to the same state
    // First set the states to the unfiltered slice size
    // This makes sure upstream evaluators have the correct input size and don't use the sliced
    // offset
    for (auto& lambdaParamEvaluator : lambdaParamEvaluators) {
        auto param = lambdaParamEvaluator->resultVector.get();
        param->state->getSelVectorUnsafe().setToUnfiltered(getSliceSize());
        savedStates.push_back(param->state);
    }

    // Then override the output sel state of the param's result vector
    // This will be a list data vector that we need to get data from using the sliced offset
    for (auto& lambdaParamEvaluator : lambdaParamEvaluators) {
        auto param = lambdaParamEvaluator->resultVector.get();
        param->state = sliceDataState;
    }
    return savedStates;
}

bool ListSliceInfo::done() const {
    return listEntryTracker.done();
}

void ListSliceInfo::restoreParamStates(
    std::span<evaluator::LambdaParamEvaluator*> lambdaParamEvaluators,
    std::vector<std::shared_ptr<common::DataChunkState>> savedStates) {
    for (size_t i = 0; i < lambdaParamEvaluators.size(); ++i) {
        auto param = lambdaParamEvaluators[i]->resultVector.get();
        param->state = savedStates[i];
    }
}

void ListSliceInfo::nextSlice() {
    updateSelVector();
}

void ListSliceInfo::updateSelVector() {
    auto& dataSel = sliceDataState->getSelVectorUnsafe();
    auto& listEntrySel = sliceListEntryState->getSelVectorUnsafe();
    common::offset_t sliceSize = 0;
    while (!listEntryTracker.done() && sliceSize < common::DEFAULT_VECTOR_CAPACITY) {
        dataSel[sliceSize] = listEntryTracker.getCurDataOffset();
        listEntrySel[sliceSize] = listEntryTracker.getListEntryPos();
        listEntryTracker.getNextDataOffset();
        ++sliceSize;
    }
    dataSel.setSelSize(sliceSize);
    listEntrySel.setSelSize(sliceSize);
}

} // namespace evaluator
} // namespace lbug
