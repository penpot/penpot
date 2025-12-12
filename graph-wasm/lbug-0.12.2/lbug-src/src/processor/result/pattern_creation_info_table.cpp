#include "processor/result/pattern_creation_info_table.h"

namespace lbug {
namespace processor {

void PatternCreationInfo::updateID(common::executor_id_t executorID,
    common::executor_info executorInfo, common::nodeID_t nodeID) const {
    if (!executorInfo.contains(executorID)) {
        return;
    }
    auto ftColIndex = executorInfo.at(executorID);
    *(common::nodeID_t*)(tuple + ftColIndex * sizeof(common::nodeID_t)) = nodeID;
}

PatternCreationInfoTable::PatternCreationInfoTable(storage::MemoryManager& memoryManager,
    std::vector<common::LogicalType> keyTypes, FactorizedTableSchema tableSchema)
    : AggregateHashTable{memoryManager, copyVector(keyTypes), std::vector<common::LogicalType>{},
          std::vector<function::AggregateFunction>{} /* empty aggregates */,
          std::vector<common::LogicalType>{} /* empty distinct agg key*/,
          0 /* numEntriesToAllocate */, tableSchema.copy()},
      tuple{nullptr}, idColOffset{tableSchema.getColOffset(keyTypes.size())} {}

PatternCreationInfo PatternCreationInfoTable::getPatternCreationInfo(
    const std::vector<common::ValueVector*>& keyVectors) {
    auto hasCreated = true;
    if (keyVectors.size() == 0) {
        // Constant keys, we can simply use one tuple to store all information
        if (factorizedTable->getNumTuples() == 0) {
            tuple = factorizedTable->appendEmptyTuple();
            hasCreated = false;
        }
        KU_ASSERT(factorizedTable->getNumTuples() == 1);
        return PatternCreationInfo{tuple, hasCreated};
    } else {
        resizeHashTableIfNecessary(1);
        computeVectorHashes(keyVectors);
        findHashSlots(keyVectors, std::vector<common::ValueVector*>{}, keyVectors[0]->state.get());
        hasCreated = tuple != nullptr;
        auto idTuple = tuple == nullptr ?
                           factorizedTable->getTuple(factorizedTable->getNumTuples() - 1) :
                           tuple;
        return PatternCreationInfo{idTuple + idColOffset, hasCreated};
    }
}

uint64_t PatternCreationInfoTable::matchFTEntries(std::span<const common::ValueVector*> keyVectors,
    uint64_t numMayMatches, uint64_t numNoMatches) {
    numNoMatches = AggregateHashTable::matchFTEntries(keyVectors, numMayMatches, numNoMatches);
    KU_ASSERT(numMayMatches <= 1);
    // If we found the entry for the target key, we set tuple to the key tuple. Otherwise, simply
    // set tuple to nullptr.
    tuple = numMayMatches != 0 ? hashSlotsToUpdateAggState[mayMatchIdxes[0]]->getEntry() : nullptr;
    return numNoMatches;
}

} // namespace processor
} // namespace lbug
