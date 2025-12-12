#pragma once

#include "common/data_chunk/data_chunk.h"

namespace lbug {
namespace common {

// TODO(Guodong): Should rework this to use ColumnChunk.
class DataChunkCollection {
public:
    explicit DataChunkCollection(storage::MemoryManager* mm);
    DELETE_COPY_DEFAULT_MOVE(DataChunkCollection);

    void append(DataChunk& chunk);
    const std::vector<DataChunk>& getChunks() const { return chunks; }
    std::vector<DataChunk>& getChunksUnsafe() { return chunks; }
    uint64_t getNumChunks() const { return chunks.size(); }
    const DataChunk& getChunk(uint64_t idx) const {
        KU_ASSERT(idx < chunks.size());
        return chunks[idx];
    }
    DataChunk& getChunkUnsafe(uint64_t idx) {
        KU_ASSERT(idx < chunks.size());
        return chunks[idx];
    }

private:
    void allocateChunk(const DataChunk& chunk);

    void initTypes(const DataChunk& chunk);

private:
    storage::MemoryManager* mm;
    std::vector<LogicalType> types;
    std::vector<DataChunk> chunks;
};

} // namespace common
} // namespace lbug
