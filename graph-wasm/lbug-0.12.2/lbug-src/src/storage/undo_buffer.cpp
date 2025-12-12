#include "storage/undo_buffer.h"

#include "catalog/catalog_entry/catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "catalog/catalog_set.h"
#include "storage/table/chunked_node_group.h"
#include "storage/table/update_info.h"
#include "storage/table/version_record_handler.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::main;

namespace lbug {
namespace storage {

struct UndoRecordHeader {
    UndoBuffer::UndoRecordType recordType;
    uint32_t recordSize;

    UndoRecordHeader(const UndoBuffer::UndoRecordType recordType, const uint32_t recordSize)
        : recordType{recordType}, recordSize{recordSize} {}
};

struct CatalogEntryRecord {
    CatalogSet* catalogSet;
    CatalogEntry* catalogEntry;
};

struct SequenceEntryRecord {
    SequenceCatalogEntry* sequenceEntry;
    SequenceRollbackData sequenceRollbackData;
};

struct NodeBatchInsertRecord {
    table_id_t tableID;
};

struct VersionRecord {
    row_idx_t startRow;
    row_idx_t numRows;
    node_group_idx_t nodeGroupIdx;
    const VersionRecordHandler* versionRecordHandler;
};

struct VectorUpdateRecord {
    UpdateInfo* updateInfo;
    idx_t vectorIdx;
    VectorUpdateInfo* vectorUpdateInfo;
    transaction_t version; // This is used during roll back.
};

template<typename F>
void UndoBufferIterator::iterate(F&& callback) {
    idx_t bufferIdx = 0;
    while (bufferIdx < undoBuffer.memoryBuffers.size()) {
        auto& currentBuffer = undoBuffer.memoryBuffers[bufferIdx];
        auto current = currentBuffer.getData();
        const auto end = current + currentBuffer.getCurrentPosition();
        while (current < end) {
            UndoRecordHeader recordHeader = *reinterpret_cast<UndoRecordHeader const*>(current);
            current += sizeof(UndoRecordHeader);
            callback(recordHeader.recordType, current);
            current += recordHeader.recordSize; // Skip the current entry.
        }
        bufferIdx++;
    }
}

template<typename F>
void UndoBufferIterator::reverseIterate(F&& callback) {
    idx_t numBuffersLeft = undoBuffer.memoryBuffers.size();
    while (numBuffersLeft > 0) {
        const auto bufferIdx = numBuffersLeft - 1;
        auto& currentBuffer = undoBuffer.memoryBuffers[bufferIdx];
        auto current = currentBuffer.getData();
        const auto end = current + currentBuffer.getCurrentPosition();
        std::vector<std::pair<UndoBuffer::UndoRecordType, uint8_t const*>> entries;
        while (current < end) {
            UndoRecordHeader recordHeader = *reinterpret_cast<UndoRecordHeader const*>(current);
            current += sizeof(UndoRecordHeader);
            entries.push_back({recordHeader.recordType, current});
            current += recordHeader.recordSize; // Skip the current entry.
        }
        for (auto i = entries.size(); i >= 1; i--) {
            callback(entries[i - 1].first, entries[i - 1].second);
        }
        numBuffersLeft--;
    }
}

void UndoBuffer::createCatalogEntry(CatalogSet& catalogSet, CatalogEntry& catalogEntry) {
    auto buffer = createUndoRecord(sizeof(UndoRecordHeader) + sizeof(CatalogEntryRecord));
    const UndoRecordHeader recordHeader{UndoRecordType::CATALOG_ENTRY, sizeof(CatalogEntryRecord)};
    *reinterpret_cast<UndoRecordHeader*>(buffer) = recordHeader;
    buffer += sizeof(UndoRecordHeader);
    const CatalogEntryRecord catalogEntryRecord{&catalogSet, &catalogEntry};
    *reinterpret_cast<CatalogEntryRecord*>(buffer) = catalogEntryRecord;
}

void UndoBuffer::createSequenceChange(SequenceCatalogEntry& sequenceEntry,
    const SequenceRollbackData& data) {
    auto buffer = createUndoRecord(sizeof(UndoRecordHeader) + sizeof(SequenceEntryRecord));
    const UndoRecordHeader recordHeader{UndoRecordType::SEQUENCE_ENTRY,
        sizeof(SequenceEntryRecord)};
    *reinterpret_cast<UndoRecordHeader*>(buffer) = recordHeader;
    buffer += sizeof(UndoRecordHeader);
    const SequenceEntryRecord sequenceEntryRecord{&sequenceEntry, data};
    *reinterpret_cast<SequenceEntryRecord*>(buffer) = sequenceEntryRecord;
}

void UndoBuffer::createInsertInfo(node_group_idx_t nodeGroupIdx, row_idx_t startRow,
    row_idx_t numRows, const VersionRecordHandler* versionRecordHandler) {
    createVersionInfo(UndoRecordType::INSERT_INFO, startRow, numRows, versionRecordHandler,
        nodeGroupIdx);
}

void UndoBuffer::createDeleteInfo(node_group_idx_t nodeGroupIdx, row_idx_t startRow,
    row_idx_t numRows, const VersionRecordHandler* versionRecordHandler) {
    createVersionInfo(UndoRecordType::DELETE_INFO, startRow, numRows, versionRecordHandler,
        nodeGroupIdx);
}

void UndoBuffer::createVersionInfo(const UndoRecordType recordType, row_idx_t startRow,
    row_idx_t numRows, const VersionRecordHandler* versionRecordHandler,
    node_group_idx_t nodeGroupIdx) {
    KU_ASSERT(versionRecordHandler);
    auto buffer = createUndoRecord(sizeof(UndoRecordHeader) + sizeof(VersionRecord));
    const UndoRecordHeader recordHeader{recordType, sizeof(VersionRecord)};
    *reinterpret_cast<UndoRecordHeader*>(buffer) = recordHeader;
    buffer += sizeof(UndoRecordHeader);
    *reinterpret_cast<VersionRecord*>(buffer) =
        VersionRecord{startRow, numRows, nodeGroupIdx, versionRecordHandler};
}

void UndoBuffer::createVectorUpdateInfo(UpdateInfo* updateInfo, const idx_t vectorIdx,
    VectorUpdateInfo* vectorUpdateInfo, transaction_t version) {
    auto buffer = createUndoRecord(sizeof(UndoRecordHeader) + sizeof(VectorUpdateRecord));
    const UndoRecordHeader recordHeader{UndoRecordType::UPDATE_INFO, sizeof(VectorUpdateRecord)};
    *reinterpret_cast<UndoRecordHeader*>(buffer) = recordHeader;
    buffer += sizeof(UndoRecordHeader);
    const VectorUpdateRecord vectorUpdateRecord{updateInfo, vectorIdx, vectorUpdateInfo, version};
    *reinterpret_cast<VectorUpdateRecord*>(buffer) = vectorUpdateRecord;
}

uint8_t* UndoBuffer::createUndoRecord(const uint64_t size) {
    std::unique_lock xLck{mtx};
    if (memoryBuffers.empty() || !memoryBuffers.back().canFit(size)) {
        auto capacity = UndoMemoryBuffer::UNDO_MEMORY_BUFFER_INIT_CAPACITY;
        while (size > capacity) {
            capacity *= 2;
        }
        // We need to allocate a new memory buffer.
        memoryBuffers.emplace_back(mm->allocateBuffer(false, capacity), capacity);
    }
    const auto res =
        memoryBuffers.back().getDataUnsafe() + memoryBuffers.back().getCurrentPosition();
    memoryBuffers.back().moveCurrentPosition(size);
    return res;
}

void UndoBuffer::commit(transaction_t commitTS) const {
    UndoBufferIterator iterator{*this};
    iterator.iterate([&](UndoRecordType entryType, uint8_t const* entry) {
        commitRecord(entryType, entry, commitTS);
    });
}

void UndoBuffer::rollback(ClientContext* context) const {
    UndoBufferIterator iterator{*this};
    iterator.reverseIterate([&](UndoRecordType entryType, uint8_t const* entry) {
        rollbackRecord(context, entryType, entry);
    });
}

void UndoBuffer::commitRecord(UndoRecordType recordType, const uint8_t* record,
    transaction_t commitTS) {
    switch (recordType) {
    case UndoRecordType::CATALOG_ENTRY: {
        commitCatalogEntryRecord(record, commitTS);
    } break;
    case UndoRecordType::SEQUENCE_ENTRY: {
        commitSequenceEntry(record, commitTS);
    } break;
    case UndoRecordType::INSERT_INFO:
    case UndoRecordType::DELETE_INFO: {
        commitVersionInfo(recordType, record, commitTS);
    } break;
    case UndoRecordType::UPDATE_INFO: {
        commitVectorUpdateInfo(record, commitTS);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void UndoBuffer::commitCatalogEntryRecord(const uint8_t* record, const transaction_t commitTS) {
    const auto& [_, catalogEntry] = *reinterpret_cast<CatalogEntryRecord const*>(record);
    const auto newCatalogEntry = catalogEntry->getNext();
    KU_ASSERT(newCatalogEntry);
    newCatalogEntry->setTimestamp(commitTS);
}

void UndoBuffer::commitVersionInfo(UndoRecordType recordType, const uint8_t* record,
    transaction_t commitTS) {
    const auto& undoRecord = *reinterpret_cast<VersionRecord const*>(record);
    switch (recordType) {
    case UndoRecordType::INSERT_INFO: {
        undoRecord.versionRecordHandler->applyFuncToChunkedGroups(&ChunkedNodeGroup::commitInsert,
            undoRecord.nodeGroupIdx, undoRecord.startRow, undoRecord.numRows, commitTS);
    } break;
    case UndoRecordType::DELETE_INFO: {
        undoRecord.versionRecordHandler->applyFuncToChunkedGroups(&ChunkedNodeGroup::commitDelete,
            undoRecord.nodeGroupIdx, undoRecord.startRow, undoRecord.numRows, commitTS);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

void UndoBuffer::commitVectorUpdateInfo(const uint8_t* record, transaction_t commitTS) {
    auto& undoRecord = *reinterpret_cast<VectorUpdateRecord const*>(record);
    KU_ASSERT(undoRecord.updateInfo);
    KU_ASSERT(undoRecord.vectorUpdateInfo);
    undoRecord.updateInfo->commit(undoRecord.vectorIdx, undoRecord.vectorUpdateInfo, commitTS);
}

void UndoBuffer::rollbackRecord(ClientContext* context, const UndoRecordType recordType,
    const uint8_t* record) {
    switch (recordType) {
    case UndoRecordType::CATALOG_ENTRY: {
        rollbackCatalogEntryRecord(record);
    } break;
    case UndoRecordType::SEQUENCE_ENTRY: {
        rollbackSequenceEntry(record);
    } break;
    case UndoRecordType::INSERT_INFO:
    case UndoRecordType::DELETE_INFO: {
        rollbackVersionInfo(context, recordType, record);
    } break;
    case UndoRecordType::UPDATE_INFO: {
        rollbackVectorUpdateInfo(record);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

void UndoBuffer::rollbackCatalogEntryRecord(const uint8_t* record) {
    const auto& [catalogSet, catalogEntry] = *reinterpret_cast<CatalogEntryRecord const*>(record);
    const auto entryToRollback = catalogEntry->getNext();
    KU_ASSERT(entryToRollback);
    if (entryToRollback->getNext()) {
        // If entryToRollback has a newer entry (next) in the version chain. Simple remove
        // entryToRollback from the chain.
        const auto newerEntry = entryToRollback->getNext();
        newerEntry->setPrev(entryToRollback->movePrev());
    } else {
        // This is the beginning of the version chain.
        auto olderEntry = entryToRollback->movePrev();
        catalogSet->eraseNoLock(catalogEntry->getName());
        if (olderEntry) {
            catalogSet->emplaceNoLock(std::move(olderEntry));
        }
    }
}

void UndoBuffer::commitSequenceEntry(const uint8_t*, transaction_t) {
    // DO NOTHING.
}

void UndoBuffer::rollbackSequenceEntry(const uint8_t* entry) {
    const auto& sequenceRecord = *reinterpret_cast<SequenceEntryRecord const*>(entry);
    const auto sequenceEntry = sequenceRecord.sequenceEntry;
    const auto& data = sequenceRecord.sequenceRollbackData;
    sequenceEntry->rollbackVal(data.usageCount, data.currVal);
}

void UndoBuffer::rollbackVersionInfo(ClientContext* context, UndoRecordType recordType,
    const uint8_t* record) {
    auto& undoRecord = *reinterpret_cast<VersionRecord const*>(record);
    switch (recordType) {
    case UndoRecordType::INSERT_INFO: {
        undoRecord.versionRecordHandler->rollbackInsert(context, undoRecord.nodeGroupIdx,
            undoRecord.startRow, undoRecord.numRows);
    } break;
    case UndoRecordType::DELETE_INFO: {
        undoRecord.versionRecordHandler->applyFuncToChunkedGroups(&ChunkedNodeGroup::rollbackDelete,
            undoRecord.nodeGroupIdx, undoRecord.startRow, undoRecord.numRows,
            transaction::Transaction::Get(*context)->getCommitTS());
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

void UndoBuffer::rollbackVectorUpdateInfo(const uint8_t* record) {
    auto& undoRecord = *reinterpret_cast<VectorUpdateRecord const*>(record);
    KU_ASSERT(undoRecord.updateInfo);
    undoRecord.updateInfo->rollback(undoRecord.vectorIdx, undoRecord.version);
}

} // namespace storage
} // namespace lbug
