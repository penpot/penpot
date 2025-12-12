#include "common/data_chunk/data_chunk_collection.h"

#include "common/system_config.h"

namespace lbug {
namespace common {

DataChunkCollection::DataChunkCollection(storage::MemoryManager* mm) : mm{mm} {}

void DataChunkCollection::append(DataChunk& chunk) {
    auto numTuplesToAppend = chunk.state->getSelVector().getSelSize();
    auto numTuplesAppended = 0u;
    while (numTuplesAppended < numTuplesToAppend) {
        if (chunks.empty() ||
            chunks.back().state->getSelVector().getSelSize() == DEFAULT_VECTOR_CAPACITY) {
            allocateChunk(chunk);
        }
        auto& chunkToAppend = chunks.back();
        auto numTuplesToCopy = std::min((uint64_t)numTuplesToAppend - numTuplesAppended,
            DEFAULT_VECTOR_CAPACITY - chunkToAppend.state->getSelVector().getSelSize());
        for (auto vectorIdx = 0u; vectorIdx < chunk.getNumValueVectors(); vectorIdx++) {
            for (auto i = 0u; i < numTuplesToCopy; i++) {
                auto srcPos = chunk.state->getSelVector()[numTuplesAppended + i];
                auto dstPos = chunkToAppend.state->getSelVector().getSelSize() + i;
                chunkToAppend.getValueVectorMutable(vectorIdx).copyFromVectorData(dstPos,
                    &chunk.getValueVector(vectorIdx), srcPos);
            }
        }
        chunkToAppend.state->getSelVectorUnsafe().incrementSelSize(numTuplesToCopy);
        numTuplesAppended += numTuplesToCopy;
    }
}

void DataChunkCollection::initTypes(const DataChunk& chunk) {
    types.clear();
    types.reserve(chunk.getNumValueVectors());
    for (auto vectorIdx = 0u; vectorIdx < chunk.getNumValueVectors(); vectorIdx++) {
        types.push_back(chunk.getValueVector(vectorIdx).dataType.copy());
    }
}

void DataChunkCollection::allocateChunk(const DataChunk& chunk) {
    if (chunks.empty()) {
        types.reserve(chunk.getNumValueVectors());
        for (auto vectorIdx = 0u; vectorIdx < chunk.getNumValueVectors(); vectorIdx++) {
            types.push_back(chunk.getValueVector(vectorIdx).dataType.copy());
        }
    }
    DataChunk newChunk(types.size(), std::make_shared<DataChunkState>());
    for (auto i = 0u; i < types.size(); i++) {
        newChunk.insert(i, std::make_shared<ValueVector>(types[i].copy(), mm));
    }
    chunks.push_back(std::move(newChunk));
}

} // namespace common
} // namespace lbug
