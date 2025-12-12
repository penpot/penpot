#pragma once

#include <array>
#include <shared_mutex>

#include "column_chunk_data.h"
#include "common/types/types.h"

namespace lbug {
namespace common {
class ValueVector;
} // namespace common

namespace transaction {
class Transaction;
} // namespace transaction

namespace storage {
class MemoryManager;

class ColumnChunkData;
struct VectorUpdateInfo {
    common::transaction_t version;
    std::array<common::sel_t, common::DEFAULT_VECTOR_CAPACITY> rowsInVector;
    common::sel_t numRowsUpdated;
    // Older versions.
    std::unique_ptr<VectorUpdateInfo> prev;
    // Newer versions.
    VectorUpdateInfo* next;

    std::unique_ptr<ColumnChunkData> data;

    VectorUpdateInfo()
        : version{common::INVALID_TRANSACTION}, rowsInVector{}, numRowsUpdated(0), prev(nullptr),
          next{nullptr}, data{nullptr} {}
    VectorUpdateInfo(MemoryManager& memoryManager, const common::transaction_t transactionID,
        common::LogicalType dataType)
        : version{transactionID}, rowsInVector{}, numRowsUpdated{0}, prev{nullptr}, next{nullptr} {
        data = ColumnChunkFactory::createColumnChunkData(memoryManager, std::move(dataType), false,
            common::DEFAULT_VECTOR_CAPACITY, ResidencyState::IN_MEMORY);
    }

    std::unique_ptr<VectorUpdateInfo> movePrev() { return std::move(prev); }
    void setPrev(std::unique_ptr<VectorUpdateInfo> prev_) { this->prev = std::move(prev_); }
    VectorUpdateInfo* getPrev() const { return prev.get(); }
    void setNext(VectorUpdateInfo* next_) { this->next = next_; }
    VectorUpdateInfo* getNext() const { return next; }
};

struct UpdateNode {
    mutable std::shared_mutex mtx;
    std::unique_ptr<VectorUpdateInfo> info;

    UpdateNode() : info{nullptr} {}
    UpdateNode(UpdateNode&& other) noexcept : info{std::move(other.info)} {}
    UpdateNode(const UpdateNode& other) = delete;

    bool isEmpty() const {
        std::shared_lock lock{mtx};
        return info != nullptr;
    }
    void clear() {
        std::unique_lock lock{mtx};
        info = nullptr;
    }
};

class UpdateInfo {
public:
    using iterate_read_from_row_func_t =
        std::function<void(const VectorUpdateInfo&, uint64_t, uint64_t)>;

    UpdateInfo() {}

    VectorUpdateInfo& update(MemoryManager& memoryManager,
        const transaction::Transaction* transaction, common::idx_t vectorIdx,
        common::sel_t rowIdxInVector, const common::ValueVector& values);

    void clearVectorInfo(common::idx_t vectorIdx) {
        std::unique_lock lock{mtx};
        updates[vectorIdx]->clear();
    }

    common::idx_t getNumVectors() const {
        std::shared_lock lock{mtx};
        return updates.size();
    }

    void scan(const transaction::Transaction* transaction, common::ValueVector& output,
        common::offset_t offsetInChunk, common::length_t length) const;
    void lookup(const transaction::Transaction* transaction, common::offset_t rowInChunk,
        common::ValueVector& output, common::sel_t posInOutputVector) const;

    void scanCommitted(const transaction::Transaction* transaction, ColumnChunkData& output,
        common::offset_t startOffsetInOutput, common::row_idx_t startRowScanned,
        common::row_idx_t numRows) const;

    void iterateVectorInfo(const transaction::Transaction* transaction, common::idx_t idx,
        const std::function<void(const VectorUpdateInfo&)>& func) const;

    void commit(common::idx_t vectorIdx, VectorUpdateInfo* info, common::transaction_t commitTS);
    void rollback(common::idx_t vectorIdx, common::transaction_t version);

    common::row_idx_t getNumUpdatedRows(const transaction::Transaction* transaction) const;

    bool hasUpdates(const transaction::Transaction* transaction, common::row_idx_t startRow,
        common::length_t numRows) const;

    bool isSet() const {
        std::shared_lock lock{mtx};
        return !updates.empty();
    }
    void reset() {
        std::unique_lock lock{mtx};
        updates.clear();
    }

    void iterateScan(const transaction::Transaction* transaction, uint64_t startOffsetToScan,
        uint64_t numRowsToScan, uint64_t startPosInOutput,
        const iterate_read_from_row_func_t& readFromRowFunc) const;

private:
    UpdateNode& getUpdateNode(common::idx_t vectorIdx);
    UpdateNode& getOrCreateUpdateNode(common::idx_t vectorIdx);

private:
    mutable std::shared_mutex mtx;
    std::vector<std::unique_ptr<UpdateNode>> updates;
};

} // namespace storage
} // namespace lbug
