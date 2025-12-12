#pragma once

#include "storage/table/chunked_node_group.h"

namespace lbug {
namespace transaction {
class Transaction;
} // namespace transaction

namespace storage {

class LBUG_API InMemChunkedNodeGroupCollection {
public:
    explicit InMemChunkedNodeGroupCollection(std::vector<common::LogicalType> types)
        : types{std::move(types)} {}
    DELETE_BOTH_COPY(InMemChunkedNodeGroupCollection);

    static std::pair<uint64_t, common::offset_t> getChunkIdxAndOffsetInChunk(
        common::row_idx_t rowIdx) {
        return std::make_pair(rowIdx / common::StorageConfig::CHUNKED_NODE_GROUP_CAPACITY,
            rowIdx % common::StorageConfig::CHUNKED_NODE_GROUP_CAPACITY);
    }

    const std::vector<std::unique_ptr<InMemChunkedNodeGroup>>& getChunkedGroups() {
        return chunkedGroups;
    }
    InMemChunkedNodeGroup& getChunkedGroup(common::node_group_idx_t groupIdx) const {
        KU_ASSERT(groupIdx < chunkedGroups.size());
        return *chunkedGroups[groupIdx];
    }

    // Return num of rows before append.
    void append(MemoryManager& memoryManager, const std::vector<common::ValueVector*>& vectors,
        common::row_idx_t startRowInVectors, common::row_idx_t numRowsToAppend);

    // `merge` are directly moving the chunkedGroup to the collection.
    void merge(std::unique_ptr<InMemChunkedNodeGroup> chunkedGroup);
    void merge(InMemChunkedNodeGroupCollection& other);

    uint64_t getNumChunkedGroups() const { return chunkedGroups.size(); }
    void clear() { chunkedGroups.clear(); }

    void loadFromDisk(MemoryManager& memoryManager) {
        for (auto& group : chunkedGroups) {
            group->loadFromDisk(memoryManager);
        }
    }

private:
    std::vector<common::LogicalType> types;
    std::vector<std::unique_ptr<InMemChunkedNodeGroup>> chunkedGroups;
};

} // namespace storage
} // namespace lbug
