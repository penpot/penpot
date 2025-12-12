#include "processor/operator/arrow_result_collector.h"

#include "common/arrow/arrow_row_batch.h"
#include "main/query_result/arrow_query_result.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

bool ArrowResultCollectorLocalState::advance() {
    for (int64_t i = static_cast<int64_t>(chunks.size()) - 1; i >= 0; --i) {
        chunkCursors[i]++;
        if (chunkCursors[i] < chunks[i]->state->getSelSize()) {
            return true;
        }
        chunkCursors[i] = 0;
    }
    return false;
}

void ArrowResultCollectorLocalState::fillTuple() {
    KU_ASSERT(tuple->len() == vectors.size());
    for (auto i = 0u; i < vectors.size(); ++i) {
        auto vector = vectors[i];
        auto pos = vector->state->getSelVector()[vectorsSelPos[i]];
        auto data = vector->getData() + pos * vector->getNumBytesPerValue();
        tuple->getValue(i)->copyFromColLayout(data, vector);
    }
}

void ArrowResultCollectorLocalState::resetCursor() {
    for (auto i = 0u; i < chunkCursors.size(); ++i) {
        chunkCursors[i] = 0;
    }
}

void ArrowResultCollectorSharedState::merge(const std::vector<ArrowArray>& localArrays) {
    std::unique_lock lck{mutex};
    for (auto i = 0u; i < localArrays.size(); ++i) {
        arrays.push_back(localArrays[i]);
    }
}

void ArrowResultCollector::executeInternal(ExecutionContext* context) {
    auto rowBatch = std::make_unique<ArrowRowBatch>(info.columnTypes, info.chunkSize,
        false /* fallbackExtensionTypes */);
    while (children[0]->getNextTuple(context)) {
        localState.resetCursor();
        while (true) {
            if (!fillRowBatch(*rowBatch)) {
                break;
            }
            localState.arrays.push_back(rowBatch->toArray(info.columnTypes));
            rowBatch = std::make_unique<ArrowRowBatch>(info.columnTypes, info.chunkSize,
                false /* fallbackExtensionTypes */);
        }
    }
    // Handle the last rowBatch whose size can be smaller than chunk size.
    if (rowBatch->size() > 0) {
        localState.arrays.push_back(rowBatch->toArray(info.columnTypes));
    }
    sharedState->merge(localState.arrays);
}

bool ArrowResultCollector::fillRowBatch(ArrowRowBatch& rowBatch) {
    while (rowBatch.size() < info.chunkSize) {
        localState.fillTuple();
        rowBatch.append(*localState.tuple);
        if (!localState.advance()) {
            return false;
        }
    }
    return true;
}

void ArrowResultCollector::initLocalStateInternal(ResultSet* resultSet, ExecutionContext*) {
    std::unordered_map<idx_t, idx_t> idxMap; // Map result set chunk idx to local state idx
    // Populate chunks
    for (auto& pos : info.payloadPositions) {
        auto idx = pos.dataChunkPos;
        if (idxMap.contains(idx)) {
            continue;
        }
        idxMap.insert({idx, localState.chunks.size()});
        localState.chunks.push_back(resultSet->getDataChunk(idx).get());
        localState.chunkCursors.push_back(0);
    }
    // Populate vectors
    for (auto& pos : info.payloadPositions) {
        localState.vectors.push_back(resultSet->getValueVector(pos).get());
        localState.vectorsSelPos.push_back(localState.chunkCursors[idxMap.at(pos.dataChunkPos)]);
    }
    localState.tuple = std::make_unique<FlatTuple>(info.columnTypes);
}

std::unique_ptr<main::QueryResult> ArrowResultCollector::getQueryResult() const {
    return std::make_unique<main::ArrowQueryResult>(std::move(sharedState->arrays), info.chunkSize);
}

} // namespace processor
} // namespace lbug
