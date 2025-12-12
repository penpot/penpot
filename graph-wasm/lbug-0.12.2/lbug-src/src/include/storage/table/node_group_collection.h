#pragma once

#include "storage/stats/table_stats.h"
#include "storage/table/group_collection.h"
#include "storage/table/node_group.h"

namespace lbug {
namespace transaction {
class Transaction;
}
namespace storage {
class MemoryManager;

class NodeGroupCollection {
public:
    NodeGroupCollection(MemoryManager& mm, const std::vector<common::LogicalType>& types,
        bool enableCompression, ResidencyState residency = ResidencyState::IN_MEMORY,
        const VersionRecordHandler* versionRecordHandler = nullptr);

    void append(const transaction::Transaction* transaction,
        const std::vector<common::ValueVector*>& vectors);
    void append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, const NodeGroupCollection& other);
    void append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, const NodeGroup& nodeGroup);

    // This function only tries to append data into the last node group, and if the last node group
    // is not enough to hold all the data, it will append partially and return the number of rows
    // appended.
    // The returned values are the startOffset and numValuesAppended.
    // NOTE: This is specially coded to only be used by NodeBatchInsert for now.
    std::pair<common::offset_t, common::offset_t> appendToLastNodeGroupAndFlushWhenFull(
        transaction::Transaction* transaction, const std::vector<common::column_id_t>& columnIDs,
        InMemChunkedNodeGroup& chunkedGroup, PageAllocator& pageAllocator);

    common::row_idx_t getNumTotalRows() const;
    common::node_group_idx_t getNumNodeGroups() const {
        const auto lock = nodeGroups.lock();
        return nodeGroups.getNumGroups(lock);
    }
    common::node_group_idx_t getNumNodeGroupsNoLock() const {
        return nodeGroups.getNumGroupsNoLock();
    }
    NodeGroup* getNodeGroupNoLock(const common::node_group_idx_t groupIdx) const {
        KU_ASSERT(nodeGroups.getGroupNoLock(groupIdx)->getNodeGroupIdx() == groupIdx);
        return nodeGroups.getGroupNoLock(groupIdx);
    }
    NodeGroup* getNodeGroup(const common::node_group_idx_t groupIdx,
        bool mayOutOfBound = false) const {
        const auto lock = nodeGroups.lock();
        if (mayOutOfBound && groupIdx >= nodeGroups.getNumGroups(lock)) {
            return nullptr;
        }
        KU_ASSERT(nodeGroups.getGroupNoLock(groupIdx)->getNodeGroupIdx() == groupIdx);
        return nodeGroups.getGroup(lock, groupIdx);
    }
    NodeGroup* getOrCreateNodeGroup(const transaction::Transaction* transaction,
        common::node_group_idx_t groupIdx, NodeGroupDataFormat format);

    void setNodeGroup(const common::node_group_idx_t nodeGroupIdx,
        std::unique_ptr<NodeGroup> group) {
        const auto lock = nodeGroups.lock();
        nodeGroups.replaceGroup(lock, nodeGroupIdx, std::move(group));
    }

    void rollbackInsert(common::row_idx_t numRows_, bool updateNumRows = true);

    void clear() {
        const auto lock = nodeGroups.lock();
        nodeGroups.clear(lock);
    }

    common::column_id_t getNumColumns() const { return types.size(); }

    void addColumn(TableAddColumnState& addColumnState, PageAllocator* pageAllocator = nullptr);

    uint64_t getEstimatedMemoryUsage() const;

    void checkpoint(MemoryManager& memoryManager, NodeGroupCheckpointState& state);
    void reclaimStorage(PageAllocator& pageAllocator) const;

    TableStats getStats() const {
        auto lock = nodeGroups.lock();
        return stats.copy();
    }
    TableStats getStats(const common::UniqLock& lock) const {
        KU_ASSERT(lock.isLocked());
        KU_UNUSED(lock);
        return stats.copy();
    }
    void mergeStats(const TableStats& stats) {
        auto lock = nodeGroups.lock();
        this->stats.merge(stats);
    }
    void mergeStats(const std::vector<common::column_id_t>& columnIDs, const TableStats& stats) {
        auto lock = nodeGroups.lock();
        this->stats.merge(columnIDs, stats);
    }

    void serialize(common::Serializer& ser);
    void deserialize(common::Deserializer& deSer, MemoryManager& memoryManager);

    void pushInsertInfo(const transaction::Transaction* transaction,
        common::node_group_idx_t nodeGroupIdx, common::row_idx_t startRow,
        common::row_idx_t numRows, const VersionRecordHandler* versionRecordHandler,
        bool incrementNumRows);

private:
    void pushInsertInfo(const transaction::Transaction* transaction, const NodeGroup* nodeGroup,
        common::row_idx_t numRows);

private:
    MemoryManager& mm;
    bool enableCompression;
    // Num rows in the collection regardless of deletions.
    std::atomic<common::row_idx_t> numTotalRows;
    std::vector<common::LogicalType> types;
    GroupCollection<NodeGroup> nodeGroups;
    ResidencyState residency;
    TableStats stats;
    const VersionRecordHandler* versionRecordHandler;
};

} // namespace storage
} // namespace lbug
