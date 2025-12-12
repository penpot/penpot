#include "processor/operator/hash_join/hash_join_probe.h"

#include "binder/expression/expression_util.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

std::string HashJoinProbePrintInfo::toString() const {
    std::string result = "Keys: ";
    result += binder::ExpressionUtil::toString(keys);
    return result;
}

void HashJoinProbe::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    probeState = std::make_unique<ProbeState>();
    for (auto& keyDataPos : probeDataInfo.keysDataPos) {
        keyVectors.push_back(resultSet->getValueVector(keyDataPos).get());
    }
    if (probeDataInfo.markDataPos.isValid()) {
        markVector = resultSet->getValueVector(probeDataInfo.markDataPos).get();
    } else {
        markVector = nullptr;
    }
    for (auto& dataPos : probeDataInfo.payloadsOutPos) {
        vectorsToReadInto.push_back(resultSet->getValueVector(dataPos).get());
    }
    // We only need to read nonKeys from the factorizedTable. Key columns are always kept as first k
    // columns in the factorizedTable, so we skip the first k columns.
    KU_ASSERT(probeDataInfo.keysDataPos.size() + probeDataInfo.getNumPayloads() + 2 ==
              sharedState->getHashTable()->getTableSchema()->getNumColumns());
    columnIdxsToReadFrom.resize(probeDataInfo.getNumPayloads());
    iota(columnIdxsToReadFrom.begin(), columnIdxsToReadFrom.end(),
        probeDataInfo.keysDataPos.size());
    auto mm = storage::MemoryManager::Get(*context->clientContext);
    hashVector = std::make_unique<ValueVector>(LogicalType::HASH(), mm);
    if (keyVectors.size() > 1) {
        tmpHashVector = std::make_unique<ValueVector>(LogicalType::HASH(), mm);
    }
}

bool HashJoinProbe::getMatchedTuplesForFlatKey(ExecutionContext* context) {
    if (probeState->nextMatchedTupleIdx < probeState->matchedSelVector.getSelSize()) {
        // Not all matched tuples have been shipped. Continue shipping.
        return true;
    }
    if (probeState->probedTuples[0] == nullptr) { // No more matched tuples on the chain.
        // We still need to save and restore for flat input because we are discarding NULL join keys
        // which changes the selected position.
        // TODO(Guodong): we have potential bugs here because all keys' states should be restored.
        restoreSelVector(*keyVectors[0]->state);
        if (!children[0]->getNextTuple(context)) {
            return false;
        }
        saveSelVector(*keyVectors[0]->state);
        sharedState->getHashTable()->probe(keyVectors, *hashVector, hashSelVec, tmpHashVector.get(),
            probeState->probedTuples.get());
    }
    auto numMatchedTuples = sharedState->getHashTable()->matchFlatKeys(keyVectors,
        probeState->probedTuples.get(), probeState->matchedTuples.get());
    probeState->matchedSelVector.setSelSize(numMatchedTuples);
    probeState->nextMatchedTupleIdx = 0;
    return true;
}

bool HashJoinProbe::getMatchedTuplesForUnFlatKey(ExecutionContext* context) {
    KU_ASSERT(keyVectors.size() == 1);
    auto keyVector = keyVectors[0];
    restoreSelVector(*keyVector->state);
    if (!children[0]->getNextTuple(context)) {
        return false;
    }
    saveSelVector(*keyVector->state);
    sharedState->getHashTable()->probe(keyVectors, *hashVector, hashSelVec, tmpHashVector.get(),
        probeState->probedTuples.get());
    auto numMatchedTuples =
        sharedState->getHashTable()->matchUnFlatKey(keyVector, probeState->probedTuples.get(),
            probeState->matchedTuples.get(), probeState->matchedSelVector);
    probeState->matchedSelVector.setSelSize(numMatchedTuples);
    probeState->nextMatchedTupleIdx = 0;
    return true;
}

uint64_t HashJoinProbe::getInnerJoinResultForFlatKey() {
    if (probeState->matchedSelVector.getSelSize() == 0) {
        return 0;
    }
    auto numTuplesToRead = 1;
    sharedState->getHashTable()->lookup(vectorsToReadInto, columnIdxsToReadFrom,
        probeState->matchedTuples.get(), probeState->nextMatchedTupleIdx, numTuplesToRead);
    probeState->nextMatchedTupleIdx += numTuplesToRead;
    return numTuplesToRead;
}

uint64_t HashJoinProbe::getInnerJoinResultForUnFlatKey() {
    auto numTuplesToRead = probeState->matchedSelVector.getSelSize();
    if (numTuplesToRead == 0) {
        return 0;
    }
    auto& keySelVector = keyVectors[0]->state->getSelVectorUnsafe();
    if (keySelVector.getSelSize() != numTuplesToRead) {
        // Some keys have no matched tuple. So we modify selected position.
        auto buffer = keySelVector.getMutableBuffer();
        for (auto i = 0u; i < numTuplesToRead; i++) {
            buffer[i] = probeState->matchedSelVector[i];
        }
        keySelVector.setToFiltered(numTuplesToRead);
    }
    sharedState->getHashTable()->lookup(vectorsToReadInto, columnIdxsToReadFrom,
        probeState->matchedTuples.get(), probeState->nextMatchedTupleIdx, numTuplesToRead);
    probeState->nextMatchedTupleIdx += numTuplesToRead;
    return numTuplesToRead;
}

static void writeLeftJoinMarkVector(ValueVector* markVector, bool flag) {
    if (markVector == nullptr) {
        return;
    }
    KU_ASSERT(markVector->state->getSelVector().getSelSize() == 1);
    auto pos = markVector->state->getSelVector()[0];
    markVector->setValue<bool>(pos, flag);
}

uint64_t HashJoinProbe::getLeftJoinResult() {
    if (getInnerJoinResult() == 0) {
        for (auto& vector : vectorsToReadInto) {
            vector->setAsSingleNullEntry();
        }
        // TODO(Xiyang): We have a bug in LEFT JOIN which should not discard NULL keys. To be more
        // clear, NULL keys should only be discarded for probe but should not reflect on the vector.
        // The following for loop is a temporary hack.
        for (auto& vector : keyVectors) {
            KU_ASSERT(vector->state->isFlat());
            vector->state->getSelVectorUnsafe().setSelSize(1);
        }
        probeState->probedTuples[0] = nullptr;
        writeLeftJoinMarkVector(markVector, false);
        return 1;
    }
    writeLeftJoinMarkVector(markVector, true);
    return 1;
}

uint64_t HashJoinProbe::getCountJoinResult() {
    KU_ASSERT(vectorsToReadInto.size() == 1);
    if (getInnerJoinResult() == 0) {
        auto pos = vectorsToReadInto[0]->state->getSelVector()[0];
        vectorsToReadInto[0]->setValue<int64_t>(pos, 0);
        probeState->probedTuples[0] = nullptr;
    }
    return 1;
}

uint64_t HashJoinProbe::getMarkJoinResult() {
    auto markValues = (bool*)markVector->getData();
    if (markVector->state->isFlat()) {
        auto pos = markVector->state->getSelVector()[0];
        markValues[pos] = probeState->matchedSelVector.getSelSize() != 0;
    } else {
        std::fill(markValues, markValues + DEFAULT_VECTOR_CAPACITY, false);
        for (auto i = 0u; i < probeState->matchedSelVector.getSelSize(); i++) {
            auto pos = probeState->matchedSelVector[i];
            markValues[pos] = true;
        }
    }
    probeState->probedTuples[0] = nullptr;
    probeState->nextMatchedTupleIdx = probeState->matchedSelVector.getSelSize();
    return 1;
}

uint64_t HashJoinProbe::getJoinResult() {
    switch (joinType) {
    case JoinType::LEFT: {
        return getLeftJoinResult();
    }
    case JoinType::COUNT: {
        return getCountJoinResult();
    }
    case JoinType::MARK: {
        return getMarkJoinResult();
    }
    case JoinType::INNER: {
        return getInnerJoinResult();
    }
    default:
        throw InternalException("Unimplemented join type for HashJoinProbe::getJoinResult()");
    }
}

// The general flow of a hash join probe:
// 1) find matched tuples of probe side key from ht.
// 2) populate values from matched tuples into resultKeyDataChunk , buildSideFlatResultDataChunk
// (all flat data chunks from the build side are merged into one) and buildSideVectorPtrs (each
// VectorPtr corresponds to one unFlat build side data chunk that is appended to the resultSet).
bool HashJoinProbe::getNextTuplesInternal(ExecutionContext* context) {
    uint64_t numPopulatedTuples = 0;
    do {
        if (!getMatchedTuples(context)) {
            return false;
        }
        numPopulatedTuples = getJoinResult();
    } while (numPopulatedTuples == 0);
    metrics->numOutputTuple.increase(numPopulatedTuples);
    return true;
}

} // namespace processor
} // namespace lbug
