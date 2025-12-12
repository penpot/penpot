#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include "common/enums/rel_multiplicity.h"
#include "common/types/types.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/buffer_manager/spill_result.h"
#include "storage/enums/residency_state.h"
#include "storage/table/column_chunk.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/version_info.h"

namespace lbug {
namespace common {
class SelectionVector;
} // namespace common

namespace transaction {
class Transaction;
} // namespace transaction

namespace storage {
class MemoryManager;

class Column;
struct TableScanState;
struct TableAddColumnState;
struct NodeGroupScanState;
class ColumnStats;
class FileHandle;
class PageAllocator;

enum class NodeGroupDataFormat : uint8_t { REGULAR = 0, CSR = 1 };

class LBUG_API InMemChunkedNodeGroup {
    friend class ChunkedNodeGroup;

public:
    virtual ~InMemChunkedNodeGroup() = default;
    InMemChunkedNodeGroup(MemoryManager& mm, const std::vector<common::LogicalType>& columnTypes,
        bool enableCompression, uint64_t capacity, common::row_idx_t startRowIdx);
    InMemChunkedNodeGroup(std::vector<std::unique_ptr<ColumnChunkData>>&& chunks,
        common::row_idx_t startRowIdx);
    // Moves the specified columns out of base
    InMemChunkedNodeGroup(InMemChunkedNodeGroup& base,
        const std::vector<common::column_id_t>& selectedColumns);

    // Also marks the chunks as in-use
    // I.e. if you want to be able to spill to disk again you must call setUnused first
    void loadFromDisk(const MemoryManager& mm);
    // returns the amount of space reclaimed in bytes
    SpillResult spillToDisk();
    void setUnused(const MemoryManager& mm);

    bool isFull() const { return numRows == capacity; }
    common::idx_t getNumColumns() const { return chunks.size(); }
    common::row_idx_t getStartRowIdx() const { return startRowIdx; }
    common::row_idx_t getNumRows() const { return numRows; }
    common::row_idx_t getCapacity() const { return capacity; }
    void setNumRows(common::offset_t numRows_);

    ColumnChunkData& getColumnChunk(const common::column_id_t columnID) {
        KU_ASSERT(columnID < chunks.size());
        return *chunks[columnID];
    }

    const ColumnChunkData& getColumnChunk(const common::column_id_t columnID) const {
        KU_ASSERT(columnID < chunks.size());
        return *chunks[columnID];
    }

    uint64_t append(const std::vector<common::ValueVector*>& columnVectors,
        common::row_idx_t startRowInVectors, uint64_t numValuesToAppend);

    // Appends up to numValuesToAppend from the other chunked node group, returning the actual
    // number of values appended.
    common::offset_t append(const InMemChunkedNodeGroup& other,
        common::offset_t offsetInOtherNodeGroup, common::offset_t numRowsToAppend);

    void resizeChunks(uint64_t newSize);
    void resetToEmpty();
    void resetToAllNull() const;

    // Moves the specified columns out of base
    void merge(InMemChunkedNodeGroup& base,
        const std::vector<common::column_id_t>& columnsToMergeInto);

    void write(const InMemChunkedNodeGroup& data, common::column_id_t offsetColumnID);
    virtual void writeToColumnChunk(common::idx_t chunkIdx, common::idx_t vectorIdx,
        const std::vector<std::unique_ptr<ColumnChunkData>>& data, ColumnChunkData& offsetChunk) {
        chunks[chunkIdx]->write(data[vectorIdx].get(), &offsetChunk, common::RelMultiplicity::ONE);
    }

    std::unique_ptr<ColumnChunkData> moveColumnChunk(const common::column_id_t columnID) {
        KU_ASSERT(columnID < chunks.size());
        return std::move(chunks[columnID]);
    }

    virtual std::unique_ptr<ChunkedNodeGroup> flush(transaction::Transaction* transaction,
        PageAllocator& pageAllocator);

protected:
    std::unique_ptr<ColumnChunk> flushInternal(ColumnChunkData& chunk,
        PageAllocator& pageAllocator);

protected:
    common::row_idx_t startRowIdx;
    std::atomic<common::row_idx_t> numRows;
    uint64_t capacity;
    std::vector<std::unique_ptr<ColumnChunkData>> chunks;
    std::mutex spillToDiskMutex;
    // Used to track if the group may be in use and to verify that spillToDisk is only called when
    // it is safe to do so. If false, it is safe to spill the data to disk.
    bool dataInUse;
};

// Collection of ColumnChunks for each column in a particular Node Group
class ChunkedNodeGroup {
    friend class InMemChunkedNodeGroup;

public:
    ChunkedNodeGroup(std::vector<std::unique_ptr<ColumnChunk>> chunks,
        common::row_idx_t startRowIdx, NodeGroupDataFormat format = NodeGroupDataFormat::REGULAR);
    // Moves the specified columns out of base
    ChunkedNodeGroup(InMemChunkedNodeGroup& base,
        const std::vector<common::column_id_t>& selectedColumns,
        NodeGroupDataFormat format = NodeGroupDataFormat::REGULAR);
    ChunkedNodeGroup(ChunkedNodeGroup& base,
        const std::vector<common::column_id_t>& selectedColumns);
    ChunkedNodeGroup(MemoryManager& mm, ChunkedNodeGroup& base,
        std::span<const common::LogicalType> columnTypes,
        std::span<const common::column_id_t> baseColumnIDs);
    ChunkedNodeGroup(MemoryManager& mm, const std::vector<common::LogicalType>& columnTypes,
        bool enableCompression, uint64_t capacity, common::row_idx_t startRowIdx,
        ResidencyState residencyState, NodeGroupDataFormat format = NodeGroupDataFormat::REGULAR);
    virtual ~ChunkedNodeGroup() = default;

    common::idx_t getNumColumns() const { return chunks.size(); }
    common::row_idx_t getStartRowIdx() const { return startRowIdx; }
    common::row_idx_t getNumRows() const { return numRows; }
    const ColumnChunk& getColumnChunk(const common::column_id_t columnID) const {
        KU_ASSERT(columnID < chunks.size());
        return *chunks[columnID];
    }
    ColumnChunk& getColumnChunk(const common::column_id_t columnID) {
        KU_ASSERT(columnID < chunks.size());
        return *chunks[columnID];
    }
    std::unique_ptr<ColumnChunk> moveColumnChunk(const common::column_id_t columnID) {
        KU_ASSERT(columnID < chunks.size());
        return std::move(chunks[columnID]);
    }
    bool isFullOrOnDisk() const {
        return numRows == capacity || residencyState == ResidencyState::ON_DISK;
    }
    ResidencyState getResidencyState() const { return residencyState; }
    NodeGroupDataFormat getFormat() const { return format; }

    void resetNumRowsFromChunks();
    void truncate(common::offset_t numRows);
    void setVersionInfo(std::unique_ptr<VersionInfo> versionInfo) {
        this->versionInfo = std::move(versionInfo);
    }
    void resetVersionAndUpdateInfo();

    uint64_t append(const transaction::Transaction* transaction,
        const std::vector<common::ValueVector*>& columnVectors, common::row_idx_t startRowInVectors,
        uint64_t numValuesToAppend);
    common::offset_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, const ChunkedNodeGroup& other,
        common::offset_t offsetInOtherNodeGroup, common::offset_t numRowsToAppend);
    common::offset_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, const InMemChunkedNodeGroup& other,
        common::offset_t offsetInOtherNodeGroup, common::offset_t numRowsToAppend);
    common::offset_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, std::span<const ColumnChunk*> other,
        common::offset_t offsetInOtherNodeGroup, common::offset_t numRowsToAppend);
    common::offset_t append(const transaction::Transaction* transaction,
        const std::vector<common::column_id_t>& columnIDs, std::span<const ColumnChunkData*> other,
        common::offset_t offsetInOtherNodeGroup, common::offset_t numRowsToAppend);

    void scan(const transaction::Transaction* transaction, const TableScanState& scanState,
        const NodeGroupScanState& nodeGroupScanState, common::offset_t rowIdxInGroup,
        common::length_t numRowsToScan) const;

    template<ResidencyState SCAN_RESIDENCY_STATE>
    void scanCommitted(transaction::Transaction* transaction, TableScanState& scanState,
        InMemChunkedNodeGroup& output) const;

    bool hasUpdates() const;
    bool hasDeletions(const transaction::Transaction* transaction) const;
    common::row_idx_t getNumUpdatedRows(const transaction::Transaction* transaction,
        common::column_id_t columnID);

    bool lookup(const transaction::Transaction* transaction, const TableScanState& state,
        const NodeGroupScanState& nodeGroupScanState, common::offset_t rowIdxInChunk,
        common::sel_t posInOutput) const;

    void update(const transaction::Transaction* transaction, common::row_idx_t rowIdxInChunk,
        common::column_id_t columnID, const common::ValueVector& propertyVector);

    bool delete_(const transaction::Transaction* transaction, common::row_idx_t rowIdxInChunk);

    void addColumn(MemoryManager& mm, const TableAddColumnState& addColumnState,
        bool enableCompression, PageAllocator* pageAllocator, ColumnStats* newColumnStats);

    bool isDeleted(const transaction::Transaction* transaction, common::row_idx_t rowInChunk) const;
    bool isInserted(const transaction::Transaction* transaction,
        common::row_idx_t rowInChunk) const;
    bool hasAnyUpdates(const transaction::Transaction* transaction, common::column_id_t columnID,
        common::row_idx_t startRow, common::length_t numRowsToCheck) const;
    common::row_idx_t getNumDeletions(const transaction::Transaction* transaction,
        common::row_idx_t startRow, common::length_t numRowsToCheck) const;
    bool hasVersionInfo() const { return versionInfo != nullptr; }

    static std::unique_ptr<ChunkedNodeGroup> flushEmpty(MemoryManager& mm,
        const std::vector<common::LogicalType>& columnTypes, bool enableCompression,
        uint64_t capacity, common::row_idx_t startRowIdx, PageAllocator& pageAllocator);

    void commitInsert(common::row_idx_t startRow, common::row_idx_t numRowsToCommit,
        common::transaction_t commitTS);
    void rollbackInsert(common::row_idx_t startRow, common::row_idx_t numRows_,
        common::transaction_t commitTS);
    void commitDelete(common::row_idx_t startRow, common::row_idx_t numRows_,
        common::transaction_t commitTS);
    void rollbackDelete(common::row_idx_t startRow, common::row_idx_t numRows_,
        common::transaction_t commitTS);
    virtual void reclaimStorage(PageAllocator& pageAllocator) const;

    uint64_t getEstimatedMemoryUsage() const;

    virtual void serialize(common::Serializer& serializer) const;
    static std::unique_ptr<ChunkedNodeGroup> deserialize(MemoryManager& memoryManager,
        common::Deserializer& deSer);

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& cast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

protected:
    NodeGroupDataFormat format;
    ResidencyState residencyState;
    common::row_idx_t startRowIdx;
    uint64_t capacity;
    std::atomic<common::row_idx_t> numRows;
    std::vector<std::unique_ptr<ColumnChunk>> chunks;
    std::unique_ptr<VersionInfo> versionInfo;
};

} // namespace storage
} // namespace lbug
