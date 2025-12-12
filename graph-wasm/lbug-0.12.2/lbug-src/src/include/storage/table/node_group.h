#pragma once

#include <cstdint>

#include "common/uniq_lock.h"
#include "storage/enums/residency_state.h"
#include "storage/table/chunked_node_group.h"
#include "storage/table/group_collection.h"
#include "storage/table/version_record_handler.h"

namespace lbug {
namespace transaction {
class Transaction;
} // namespace transaction

namespace storage {
class MemoryManager;

class ColumnStats;
struct TableAddColumnState;
class NodeGroup;

struct NodeGroupScanState {
    // Index of committed but not yet checkpointed chunked group to scan.
    common::idx_t chunkedGroupIdx = 0;
    common::row_idx_t nextRowToScan = 0;
    // State of each chunk in the checkpointed chunked group.
    std::vector<ChunkState> chunkStates;

    explicit NodeGroupScanState() {}
    explicit NodeGroupScanState(common::idx_t numChunks) { chunkStates.resize(numChunks); }

    virtual ~NodeGroupScanState() = default;
    DELETE_COPY_DEFAULT_MOVE(NodeGroupScanState);

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

struct NodeGroupCheckpointState {
    std::vector<common::column_id_t> columnIDs;
    std::vector<Column*> columns;
    PageAllocator& pageAllocator;
    MemoryManager* mm;

    NodeGroupCheckpointState(std::vector<common::column_id_t> columnIDs,
        std::vector<Column*> columns, PageAllocator& pageAllocator, MemoryManager* mm)
        : columnIDs{std::move(columnIDs)}, columns{std::move(columns)},
          pageAllocator{pageAllocator}, mm{mm} {}
    virtual ~NodeGroupCheckpointState() = default;

    template<typename T>
    const T& cast() const {
        return common::ku_dynamic_cast<const T&>(*this);
    }
    template<typename T>
    T& cast() {
        return common::ku_dynamic_cast<T&>(*this);
    }
};

struct NodeGroupScanResult {

    common::row_idx_t startRow = common::INVALID_ROW_IDX;
    common::row_idx_t numRows = 0;

    constexpr NodeGroupScanResult() noexcept = default;
    constexpr NodeGroupScanResult(common::row_idx_t startRow, common::row_idx_t numRows) noexcept
        : startRow{startRow}, numRows{numRows} {}

    bool operator==(const NodeGroupScanResult& other) const {
        return startRow == other.startRow && numRows == other.numRows;
    }
};

static auto NODE_GROUP_SCAN_EMPTY_RESULT = NodeGroupScanResult{};

struct TableScanState;
class NodeGroup {
public:
    NodeGroup(MemoryManager& mm, const common::node_group_idx_t nodeGroupIdx,
        const bool enableCompression, std::vector<common::LogicalType> dataTypes,
        common::row_idx_t capacity = common::StorageConfig::NODE_GROUP_SIZE,
        NodeGroupDataFormat format = NodeGroupDataFormat::REGULAR)
        : mm{mm}, nodeGroupIdx{nodeGroupIdx}, format{format}, enableCompression{enableCompression},
          numRows{0}, nextRowToAppend{0}, capacity{capacity}, dataTypes{std::move(dataTypes)} {}
    NodeGroup(MemoryManager& mm, const common::node_group_idx_t nodeGroupIdx,
        const bool enableCompression, std::unique_ptr<ChunkedNodeGroup> chunkedNodeGroup,
        common::row_idx_t capacity = common::StorageConfig::NODE_GROUP_SIZE,
        NodeGroupDataFormat format = NodeGroupDataFormat::REGULAR)
        : mm{mm}, nodeGroupIdx{nodeGroupIdx}, format{format}, enableCompression{enableCompression},
          numRows{chunkedNodeGroup->getStartRowIdx() + chunkedNodeGroup->getNumRows()},
          nextRowToAppend{numRows}, capacity{capacity} {
        for (auto i = 0u; i < chunkedNodeGroup->getNumColumns(); i++) {
            dataTypes.push_back(chunkedNodeGroup->getColumnChunk(i).getDataType().copy());
        }
        const auto lock = chunkedGroups.lock();
        chunkedGroups.appendGroup(lock, std::move(chunkedNodeGroup));
    }
    NodeGroup(MemoryManager& mm, const common::node_group_idx_t nodeGroupIdx,
        const bool enableCompression, common::row_idx_t capacity, NodeGroupDataFormat format)
        : mm{mm}, nodeGroupIdx{nodeGroupIdx}, format{format}, enableCompression{enableCompression},
          numRows{0}, nextRowToAppend{0}, capacity{capacity} {}
    virtual ~NodeGroup() = default;

    virtual bool isEmpty() const { return numRows.load() == 0; }
    virtual common::row_idx_t getNumRows() const { return numRows.load(); }
    void moveNextRowToAppend(common::row_idx_t numRowsToAppend) {
        nextRowToAppend += numRowsToAppend;
    }
    common::row_idx_t getNumRowsLeftToAppend() const { return capacity - nextRowToAppend; }
    bool isFull() const { return numRows.load() == capacity; }
    const std::vector<common::LogicalType>& getDataTypes() const { return dataTypes; }
    NodeGroupDataFormat getFormat() const { return format; }
    common::row_idx_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, ChunkedNodeGroup& chunkedGroup,
        common::row_idx_t startRowIdx, common::row_idx_t numRowsToAppend);
    common::row_idx_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, InMemChunkedNodeGroup& chunkedGroup,
        common::row_idx_t startRowIdx, common::row_idx_t numRowsToAppend);
    common::row_idx_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs,
        std::span<const ColumnChunkData*> chunkedGroup, common::row_idx_t startRowIdx,
        common::row_idx_t numRowsToAppend);
    common::row_idx_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs,
        std::span<const ColumnChunk*> chunkedGroup, common::row_idx_t startRowIdx,
        common::row_idx_t numRowsToAppend);
    void append(const transaction::Transaction* transaction,
        const std::vector<common::ValueVector*>& vectors, common::row_idx_t startRowIdx,
        common::row_idx_t numRowsToAppend);

    void merge(transaction::Transaction* transaction,
        std::unique_ptr<ChunkedNodeGroup> chunkedGroup);

    virtual void initializeScanState(const transaction::Transaction* transaction,
        TableScanState& state) const;
    void initializeScanState(const transaction::Transaction* transaction,
        const common::UniqLock& lock, TableScanState& state) const;
    virtual NodeGroupScanResult scan(const transaction::Transaction* transaction,
        TableScanState& state) const;

    virtual NodeGroupScanResult scan(transaction::Transaction* transaction, TableScanState& state,
        common::offset_t startOffsetInGroup, common::offset_t numRowsToScan) const;

    bool lookup(const transaction::Transaction* transaction, const TableScanState& state,
        common::sel_t posInSel = 0) const;
    bool lookupNoLock(const transaction::Transaction* transaction, const TableScanState& state,
        common::sel_t posInSel = 0) const;
    // TODO(Guodong): These should be merged together with `lookup`.
    bool lookupMultiple(const common::UniqLock& lock, const transaction::Transaction* transaction,
        const TableScanState& state) const;
    bool lookupMultiple(const transaction::Transaction* transaction,
        const TableScanState& state) const;

    void update(const transaction::Transaction* transaction, common::row_idx_t rowIdxInGroup,
        common::column_id_t columnID, const common::ValueVector& propertyVector);
    bool delete_(const transaction::Transaction* transaction, common::row_idx_t rowIdxInGroup);

    bool hasDeletions(const transaction::Transaction* transaction) const;
    virtual void addColumn(TableAddColumnState& addColumnState, PageAllocator* pageAllocator,
        ColumnStats* newColumnStats);

    void applyFuncToChunkedGroups(version_record_handler_op_t func, common::row_idx_t startRow,
        common::row_idx_t numRows, common::transaction_t commitTS) const;
    void rollbackInsert(common::row_idx_t startRow);
    void reclaimStorage(PageAllocator& pageAllocator) const;
    virtual void reclaimStorage(PageAllocator& pageAllocator, const common::UniqLock& lock) const;

    virtual void checkpoint(MemoryManager& memoryManager, NodeGroupCheckpointState& state);

    uint64_t getEstimatedMemoryUsage() const;

    virtual void serialize(common::Serializer& serializer);
    static std::unique_ptr<NodeGroup> deserialize(MemoryManager& mm, common::Deserializer& deSer,
        const std::vector<common::LogicalType>& columnTypes);

    common::node_group_idx_t getNumChunkedGroups() const {
        const auto lock = chunkedGroups.lock();
        return chunkedGroups.getNumGroups(lock);
    }
    ChunkedNodeGroup* getChunkedNodeGroup(common::node_group_idx_t groupIdx) const {
        const auto lock = chunkedGroups.lock();
        return chunkedGroups.getGroup(lock, groupIdx);
    }

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& cast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

    bool isVisible(const transaction::Transaction* transaction,
        common::row_idx_t rowIdxInGroup) const;
    bool isVisibleNoLock(const transaction::Transaction* transaction,
        common::row_idx_t rowIdxInGroup) const;
    bool isDeleted(const transaction::Transaction* transaction,
        common::offset_t offsetInGroup) const;
    bool isInserted(const transaction::Transaction* transaction,
        common::offset_t offsetInGroup) const;

    common::node_group_idx_t getNodeGroupIdx() const { return nodeGroupIdx; }

protected:
    static constexpr auto INVALID_CHUNKED_GROUP_IDX = UINT32_MAX;
    static constexpr auto INVALID_START_ROW_IDX = UINT64_MAX;

protected:
    void checkpointDataTypesNoLock(const NodeGroupCheckpointState& state);

private:
    std::pair<common::idx_t, common::row_idx_t> findChunkedGroupIdxFromRowIdxNoLock(
        common::row_idx_t rowIdx) const;
    ChunkedNodeGroup* findChunkedGroupFromRowIdx(const common::UniqLock& lock,
        common::row_idx_t rowIdx) const;
    ChunkedNodeGroup* findChunkedGroupFromRowIdxNoLock(common::row_idx_t rowIdx) const;

    std::unique_ptr<ChunkedNodeGroup> checkpointInMemOnly(MemoryManager& memoryManager,
        const common::UniqLock& lock, const NodeGroupCheckpointState& state) const;
    std::unique_ptr<ChunkedNodeGroup> checkpointInMemAndOnDisk(MemoryManager& memoryManager,
        const common::UniqLock& lock, NodeGroupCheckpointState& state) const;
    std::unique_ptr<VersionInfo> checkpointVersionInfo(const common::UniqLock& lock,
        const transaction::Transaction* transaction) const;

    template<ResidencyState SCAN_RESIDENCY_STATE>
    common::row_idx_t getNumResidentRows(const common::UniqLock& lock) const;
    template<ResidencyState SCAN_RESIDENCY_STATE>
    std::unique_ptr<InMemChunkedNodeGroup> scanAllInsertedAndVersions(MemoryManager& memoryManager,
        const common::UniqLock& lock, const std::vector<common::column_id_t>& columnIDs,
        const std::vector<const Column*>& columns) const;

    virtual NodeGroupScanResult scanInternal(const common::UniqLock& lock,
        transaction::Transaction* transaction, TableScanState& state,
        common::offset_t startOffsetInGroup, common::offset_t numRowsToScan) const;

    common::row_idx_t getStartRowIdxInGroupNoLock() const;
    common::row_idx_t getStartRowIdxInGroup(const common::UniqLock& lock) const;

    void scanCommittedUpdatesForColumn(std::vector<ChunkCheckpointState>& chunkCheckpointStates,
        MemoryManager& memoryManager, const common::UniqLock& lock, common::column_id_t columnID,
        const Column* column) const;

protected:
    MemoryManager& mm;
    common::node_group_idx_t nodeGroupIdx;
    NodeGroupDataFormat format;
    bool enableCompression;
    std::atomic<common::row_idx_t> numRows;
    // `nextRowToAppend` is a cursor to allow us to pre-reserve a set of rows to append before
    // acutally appending data. This is an optimization to reduce lock-contention when appending in
    // parallel.
    // TODO(Guodong): Remove this field.
    common::row_idx_t nextRowToAppend;
    common::row_idx_t capacity;
    std::vector<common::LogicalType> dataTypes;
    GroupCollection<ChunkedNodeGroup> chunkedGroups;
};

} // namespace storage
} // namespace lbug
