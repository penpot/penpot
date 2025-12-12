#include "storage/table/update_info.h"

#include <bitset>

#include "common/exception/runtime.h"
#include "common/vector/value_vector.h"
#include "storage/storage_utils.h"
#include "storage/table/column_chunk_data.h"
#include "transaction/transaction.h"

using namespace lbug::transaction;
using namespace lbug::common;

namespace lbug {
namespace storage {

VectorUpdateInfo& UpdateInfo::update(MemoryManager& memoryManager, const Transaction* transaction,
    const idx_t vectorIdx, const sel_t rowIdxInVector, const ValueVector& values) {
    UpdateNode& header = getOrCreateUpdateNode(vectorIdx);
    // We always lock the head of the chain of vectorUpdateInfo to ensure that we can safely
    // read/write to any part of the chain.
    std::unique_lock chainLock{header.mtx};
    // Traverse the chain of vectorUpdateInfo to find the one that matches the transaction. Also
    // detect if there is any write-write conflicts.
    auto current = header.info.get();
    VectorUpdateInfo* vecUpdateInfo = nullptr;
    while (current) {
        if (current->version == transaction->getID()) {
            // Same transaction, we can update the existing vector info.
            KU_ASSERT(current->version >= Transaction::START_TRANSACTION_ID);
            vecUpdateInfo = current;
        } else if (current->version > transaction->getStartTS()) {
            // Potentially there can be conflicts. `current` can be uncommitted transaction (version
            // is transaction ID) or committed transaction started after this transaction.
            for (auto i = 0u; i < current->numRowsUpdated; i++) {
                if (current->rowsInVector[i] == rowIdxInVector) {
                    throw RuntimeException("Write-write conflict of updating the same row.");
                }
            }
        }
        current = current->prev.get();
    }
    if (!vecUpdateInfo) {
        // Create a new version here if not found in the chain.
        auto newInfo = std::make_unique<VectorUpdateInfo>(memoryManager, transaction->getID(),
            values.dataType.copy());
        vecUpdateInfo = newInfo.get();
        auto currentInfo = std::move(header.info);
        if (currentInfo) {
            currentInfo->next = newInfo.get();
        }
        newInfo->prev = std::move(currentInfo);
        header.info = std::move(newInfo);
    }
    KU_ASSERT(vecUpdateInfo);
    // Check if the row is already updated in this transaction.
    idx_t idxInUpdateData = INVALID_IDX;
    for (auto i = 0u; i < vecUpdateInfo->numRowsUpdated; i++) {
        if (vecUpdateInfo->rowsInVector[i] == rowIdxInVector) {
            idxInUpdateData = i;
            break;
        }
    }
    if (idxInUpdateData != INVALID_IDX) {
        // Overwrite existing update value.
        vecUpdateInfo->data->write(&values, values.state->getSelVector()[0], idxInUpdateData);
    } else {
        // Append new value and update `rowsInVector`.
        vecUpdateInfo->rowsInVector[vecUpdateInfo->numRowsUpdated] = rowIdxInVector;
        vecUpdateInfo->data->write(&values, values.state->getSelVector()[0],
            vecUpdateInfo->numRowsUpdated++);
    }
    return *vecUpdateInfo;
}

void UpdateInfo::scan(const Transaction* transaction, ValueVector& output, offset_t offsetInChunk,
    length_t length) const {
    iterateScan(transaction, offsetInChunk, length, 0 /* startPosInOutput */,
        [&](const VectorUpdateInfo& vecUpdateInfo, uint64_t i, uint64_t posInOutput) -> void {
            vecUpdateInfo.data->lookup(i, output, posInOutput);
        });
}

void UpdateInfo::lookup(const Transaction* transaction, offset_t rowInChunk, ValueVector& output,
    sel_t posInOutputVector) const {
    if (!isSet()) {
        return;
    }
    auto [vectorIdx, rowInVector] =
        StorageUtils::getQuotientRemainder(rowInChunk, DEFAULT_VECTOR_CAPACITY);
    bool updated = false;
    iterateVectorInfo(transaction, vectorIdx, [&](const VectorUpdateInfo& vectorInfo) {
        if (updated) {
            return;
        }
        for (auto i = 0u; i < vectorInfo.numRowsUpdated; i++) {
            if (vectorInfo.rowsInVector[i] == rowInVector) {
                vectorInfo.data->lookup(i, output, posInOutputVector);
                updated = true;
                return;
            }
        }
    });
}

void UpdateInfo::scanCommitted(const Transaction* transaction, ColumnChunkData& output,
    offset_t startOffsetInOutput, row_idx_t startRowScanned, row_idx_t numRows) const {
    iterateScan(transaction, startRowScanned, numRows, startOffsetInOutput,
        [&](const VectorUpdateInfo& vecUpdateInfo, uint64_t i, uint64_t posInOutput) -> void {
            output.write(vecUpdateInfo.data.get(), i, posInOutput, 1);
        });
}

void UpdateInfo::iterateVectorInfo(const Transaction* transaction, idx_t idx,
    const std::function<void(const VectorUpdateInfo&)>& func) const {
    const UpdateNode* head = nullptr;
    {
        std::shared_lock lock{mtx};
        if (idx >= updates.size() || !updates[idx]->isEmpty()) {
            return;
        }
        head = updates[idx].get();
    }
    // We lock the head of the chain to ensure that we can safely read from any part of the
    // chain.
    KU_ASSERT(head);
    std::shared_lock chainLock{head->mtx};
    auto current = head->info.get();
    KU_ASSERT(current);
    while (current) {
        if (current->version == transaction->getID() ||
            current->version <= transaction->getStartTS()) {
            KU_ASSERT((current->version == transaction->getID() &&
                          current->version >= Transaction::START_TRANSACTION_ID) ||
                      (current->version <= transaction->getStartTS() &&
                          current->version < Transaction::START_TRANSACTION_ID));
            func(*current);
        }
        current = current->getPrev();
    }
}

#if defined(LBUG_RUNTIME_CHECKS) || !defined(NDEBUG)
// Assert that info is in the updatedNode version chain.
static bool validateUpdateChain(const UpdateNode& updatedNode, const VectorUpdateInfo* info) {
    auto current = updatedNode.info.get();
    while (current) {
        if (current == info) {
            return true;
        }
        current = current->getPrev();
    }
    return false;
}
#endif

void UpdateInfo::commit(idx_t vectorIdx, VectorUpdateInfo* info, transaction_t commitTS) {
    auto& updateNode = getUpdateNode(vectorIdx);
    std::unique_lock chainLock{updateNode.mtx};
    KU_ASSERT(validateUpdateChain(updateNode, info));
    info->version = commitTS;
}

void UpdateInfo::rollback(idx_t vectorIdx, transaction_t version) {
    UpdateNode* header = nullptr;
    // Note that we lock the entire UpdateInfo structure here because we might modify the
    // head of the version chain. This is just a simplification and should be optimized later.
    {
        std::unique_lock lock{mtx};
        KU_ASSERT(updates.size() > vectorIdx);
        header = updates[vectorIdx].get();
    }
    KU_ASSERT(header);
    std::unique_lock chainLock{header->mtx};
    // First check if this version is still in the chain. It might have been removed by
    // a previous rollback entry of the same transaction.
    // TODO(Guodong): This will be optimized by moving VectorUpdateInfo into UndoBuffer.
    auto current = header->info.get();
    while (current) {
        if (current->version == version) {
            auto prevVersion = current->movePrev();
            if (current->next) {
                // Has newer version. Remove this from the version chain.
                const auto newerVersion = current->next;
                if (prevVersion) {
                    prevVersion->next = newerVersion;
                }
                newerVersion->setPrev(std::move(prevVersion));
            } else {
                KU_ASSERT(header->info.get() == current);
                // This is the beginning of the version chain.
                if (prevVersion) {
                    prevVersion->next = nullptr;
                }
                header->info = std::move(prevVersion);
            }
            break;
        }
        current = current->getPrev();
    }
}

row_idx_t UpdateInfo::getNumUpdatedRows(const Transaction* transaction) const {
    std::unordered_set<row_idx_t> updatedRows;
    for (auto vectorIdx = 0u; vectorIdx < updates.size(); vectorIdx++) {
        iterateVectorInfo(transaction, vectorIdx, [&](const VectorUpdateInfo& info) {
            for (auto i = 0u; i < info.numRowsUpdated; i++) {
                updatedRows.insert(info.rowsInVector[i]);
            }
        });
    }
    return updatedRows.size();
}

bool UpdateInfo::hasUpdates(const Transaction* transaction, row_idx_t startRow,
    length_t numRows) const {
    bool hasUpdates = false;
    iterateScan(transaction, startRow, numRows, 0 /* startPosInOutput */,
        [&](const VectorUpdateInfo&, uint64_t, uint64_t) -> void { hasUpdates = true; });
    return hasUpdates;
}

UpdateNode& UpdateInfo::getUpdateNode(idx_t vectorIdx) {
    std::shared_lock lock{mtx};
    if (vectorIdx >= updates.size()) {
        throw InternalException(
            "UpdateInfo does not have update node for vector index: " + std::to_string(vectorIdx));
    }
    return *updates[vectorIdx];
}

UpdateNode& UpdateInfo::getOrCreateUpdateNode(idx_t vectorIdx) {
    std::unique_lock lock{mtx};
    if (vectorIdx >= updates.size()) {
        updates.resize(vectorIdx + 1);
        for (auto i = 0u; i < updates.size(); i++) {
            if (!updates[i]) {
                updates[i] = std::make_unique<UpdateNode>();
            }
        }
    }
    return *updates[vectorIdx];
}

void UpdateInfo::iterateScan(const Transaction* transaction, uint64_t startOffsetToScan,
    uint64_t numRowsToScan, uint64_t startPosInOutput,
    const iterate_read_from_row_func_t& readFromRowFunc) const {
    if (!isSet()) {
        return;
    }
    auto [startVectorIdx, startOffsetInVector] =
        StorageUtils::getQuotientRemainder(startOffsetToScan, DEFAULT_VECTOR_CAPACITY);
    auto [endVectorIdx, endOffsetInVector] = StorageUtils::getQuotientRemainder(
        startOffsetToScan + numRowsToScan, DEFAULT_VECTOR_CAPACITY);
    idx_t idx = startVectorIdx;
    sel_t posInVector = startPosInOutput;
    while (idx <= endVectorIdx) {
        const auto startOffsetInclusively = idx == startVectorIdx ? startOffsetInVector : 0;
        const auto endOffsetExclusively =
            idx == endVectorIdx ? endOffsetInVector : DEFAULT_VECTOR_CAPACITY;
        const auto numRowsInVector = endOffsetExclusively - startOffsetInclusively;
        // We keep track of the rows that have been applied with updates from updateInfo. The update
        // version chain is maintained with the newest version at the head and the oldest version at
        // the tail. For each tuple, we iterate through the chain to merge the updates from latest
        // visible version. If a row has been updated in the current vectorInfo, we should skip it
        // in older versions.
        std::bitset<DEFAULT_VECTOR_CAPACITY> rowsUpdated;
        iterateVectorInfo(transaction, idx, [&](const VectorUpdateInfo& vecUpdateInfo) -> void {
            if (vecUpdateInfo.numRowsUpdated == 0) {
                return;
            }
            if (rowsUpdated.count() == numRowsInVector) {
                // All rows in this vector have been updated with a newer visible version already.
                return;
            }
            // TODO(Guodong): Ideally we should make sure vecUpdateInfo.rowsInVector is sorted to
            // simplify the checks here.
            for (auto i = 0u; i < vecUpdateInfo.numRowsUpdated; i++) {
                if (vecUpdateInfo.rowsInVector[i] < startOffsetInclusively ||
                    vecUpdateInfo.rowsInVector[i] >= endOffsetExclusively) {
                    // Continue if the row is out of the current scan range.
                    continue;
                }
                auto updatedRowIdx = vecUpdateInfo.rowsInVector[i] - startOffsetInclusively;
                if (rowsUpdated[updatedRowIdx]) {
                    // Skip the rows that have been updated with a newer visible version already.
                    continue;
                }
                readFromRowFunc(vecUpdateInfo, i, posInVector + updatedRowIdx);
                rowsUpdated[updatedRowIdx] = true;
            }
        });
        posInVector += numRowsInVector;
        idx++;
    }
}

} // namespace storage
} // namespace lbug
