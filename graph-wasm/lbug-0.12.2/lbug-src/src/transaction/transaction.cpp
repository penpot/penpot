#include "transaction/transaction.h"

#include "common/exception/runtime.h"
#include "main/client_context.h"
#include "main/db_config.h"
#include "storage/local_storage/local_node_table.h"
#include "storage/local_storage/local_storage.h"
#include "storage/storage_manager.h"
#include "storage/undo_buffer.h"
#include "storage/wal/local_wal.h"
#include "transaction/transaction_context.h"

using namespace lbug::catalog;

namespace lbug {
namespace transaction {

bool LocalCacheManager::put(std::unique_ptr<LocalCacheObject> object) {
    std::unique_lock lck{mtx};
    const auto key = object->getKey();
    if (cachedObjects.contains(key)) {
        return false;
    }
    cachedObjects[object->getKey()] = std::move(object);
    return true;
}

Transaction::Transaction(main::ClientContext& clientContext, TransactionType transactionType,
    common::transaction_t transactionID, common::transaction_t startTS)
    : type{transactionType}, ID{transactionID}, startTS{startTS},
      commitTS{common::INVALID_TRANSACTION}, forceCheckpoint{false}, hasCatalogChanges{false} {
    this->clientContext = &clientContext;
    localStorage = std::make_unique<storage::LocalStorage>(clientContext);
    undoBuffer = std::make_unique<storage::UndoBuffer>(storage::MemoryManager::Get(clientContext));
    currentTS = common::Timestamp::getCurrentTimestamp().value;
    localWAL = std::make_unique<storage::LocalWAL>(*storage::MemoryManager::Get(clientContext),
        clientContext.getDBConfig()->enableChecksums);
}

Transaction::Transaction(TransactionType transactionType) noexcept
    : type{transactionType}, ID{DUMMY_TRANSACTION_ID}, startTS{DUMMY_START_TIMESTAMP},
      commitTS{common::INVALID_TRANSACTION}, clientContext{nullptr}, undoBuffer{nullptr},
      forceCheckpoint{false}, hasCatalogChanges{false} {
    currentTS = common::Timestamp::getCurrentTimestamp().value;
}

Transaction::Transaction(TransactionType transactionType, common::transaction_t ID,
    common::transaction_t startTS) noexcept
    : type{transactionType}, ID{ID}, startTS{startTS}, commitTS{common::INVALID_TRANSACTION},
      clientContext{nullptr}, undoBuffer{nullptr}, forceCheckpoint{false},
      hasCatalogChanges{false} {
    currentTS = common::Timestamp::getCurrentTimestamp().value;
}

bool Transaction::shouldLogToWAL() const {
    return isWriteTransaction() && !clientContext->isInMemory();
}

bool Transaction::shouldForceCheckpoint() const {
    return !clientContext->isInMemory() && forceCheckpoint;
}

void Transaction::commit(storage::WAL* wal) {
    localStorage->commit();
    undoBuffer->commit(commitTS);
    if (shouldLogToWAL()) {
        KU_ASSERT(localWAL && wal);
        localWAL->logCommit();
        wal->logCommittedWAL(*localWAL, clientContext);
        localWAL->clear();
    }
    if (hasCatalogChanges) {
        Catalog::Get(*clientContext)->incrementVersion();
        hasCatalogChanges = false;
    }
}

void Transaction::rollback(storage::WAL*) {
    // Rolling back the local storage will free + evict all optimistically-allocated pages
    // Since the undo buffer may do some scanning (e.g. to delete inserted keys from the hash index)
    // this must be rolled back first
    undoBuffer->rollback(clientContext);
    localStorage->rollback();
    hasCatalogChanges = false;
}

bool Transaction::isUnCommitted(common::table_id_t tableID, common::offset_t nodeOffset) const {
    return localStorage && localStorage->getLocalTable(tableID) &&
           nodeOffset >= getMinUncommittedNodeOffset(tableID);
}

void Transaction::pushCreateDropCatalogEntry(CatalogSet& catalogSet, CatalogEntry& catalogEntry,
    bool isInternal, bool skipLoggingToWAL) {
    undoBuffer->createCatalogEntry(catalogSet, catalogEntry);
    hasCatalogChanges = true;
    if (!shouldLogToWAL() || skipLoggingToWAL) {
        return;
    }
    KU_ASSERT(localWAL);
    const auto newCatalogEntry = catalogEntry.getNext();
    switch (newCatalogEntry->getType()) {
    case CatalogEntryType::INDEX_ENTRY:
    case CatalogEntryType::NODE_TABLE_ENTRY:
    case CatalogEntryType::REL_GROUP_ENTRY: {
        if (catalogEntry.getType() == CatalogEntryType::DUMMY_ENTRY) {
            KU_ASSERT(catalogEntry.isDeleted());
            localWAL->logCreateCatalogEntryRecord(newCatalogEntry, isInternal);
        } else {
            throw common::RuntimeException("This shouldn't happen. Alter table is not supported.");
        }
    } break;
    case CatalogEntryType::SEQUENCE_ENTRY: {
        KU_ASSERT(
            catalogEntry.getType() == CatalogEntryType::DUMMY_ENTRY && catalogEntry.isDeleted());
        if (newCatalogEntry->hasParent()) {
            // We don't log SERIAL catalog entry creation as it is implicit
            return;
        }
        localWAL->logCreateCatalogEntryRecord(newCatalogEntry, isInternal);
    } break;
    case CatalogEntryType::SCALAR_MACRO_ENTRY:
    case CatalogEntryType::TYPE_ENTRY: {
        KU_ASSERT(
            catalogEntry.getType() == CatalogEntryType::DUMMY_ENTRY && catalogEntry.isDeleted());
        localWAL->logCreateCatalogEntryRecord(newCatalogEntry, isInternal);
    } break;
    case CatalogEntryType::DUMMY_ENTRY: {
        KU_ASSERT(newCatalogEntry->isDeleted());
        if (catalogEntry.hasParent()) {
            return;
        }
        switch (catalogEntry.getType()) {
        case CatalogEntryType::INDEX_ENTRY:
        case CatalogEntryType::SCALAR_MACRO_ENTRY:
        case CatalogEntryType::NODE_TABLE_ENTRY:
        case CatalogEntryType::REL_GROUP_ENTRY:
        case CatalogEntryType::SEQUENCE_ENTRY: {
            localWAL->logDropCatalogEntryRecord(catalogEntry.getOID(), catalogEntry.getType());
        } break;
        case CatalogEntryType::SCALAR_FUNCTION_ENTRY:
        case CatalogEntryType::TABLE_FUNCTION_ENTRY:
        case CatalogEntryType::STANDALONE_TABLE_FUNCTION_ENTRY: {
            // DO NOTHING. We don't persist function entries.
        } break;
        case CatalogEntryType::TYPE_ENTRY:
        default: {
            throw common::RuntimeException(
                common::stringFormat("Not supported catalog entry type {} yet.",
                    CatalogEntryTypeUtils::toString(catalogEntry.getType())));
        }
        }
    } break;
    case CatalogEntryType::SCALAR_FUNCTION_ENTRY:
    case CatalogEntryType::TABLE_FUNCTION_ENTRY:
    case CatalogEntryType::STANDALONE_TABLE_FUNCTION_ENTRY: {
        // DO NOTHING. We don't persist function entries.
    } break;
    default: {
        throw common::RuntimeException(
            common::stringFormat("Not supported catalog entry type {} yet.",
                CatalogEntryTypeUtils::toString(catalogEntry.getType())));
    }
    }
}

void Transaction::pushAlterCatalogEntry(CatalogSet& catalogSet, CatalogEntry& catalogEntry,
    const binder::BoundAlterInfo& alterInfo) {
    undoBuffer->createCatalogEntry(catalogSet, catalogEntry);
    hasCatalogChanges = true;
    if (shouldLogToWAL()) {
        KU_ASSERT(localWAL);
        localWAL->logAlterCatalogEntryRecord(&alterInfo);
    }
}

void Transaction::pushSequenceChange(SequenceCatalogEntry* sequenceEntry, int64_t kCount,
    const SequenceRollbackData& data) {
    undoBuffer->createSequenceChange(*sequenceEntry, data);
    hasCatalogChanges = true;
    if (shouldLogToWAL()) {
        KU_ASSERT(localWAL);
        localWAL->logUpdateSequenceRecord(sequenceEntry->getOID(), kCount);
    }
}

void Transaction::pushInsertInfo(common::node_group_idx_t nodeGroupIdx, common::row_idx_t startRow,
    common::row_idx_t numRows, const storage::VersionRecordHandler* versionRecordHandler) const {
    undoBuffer->createInsertInfo(nodeGroupIdx, startRow, numRows, versionRecordHandler);
}

void Transaction::pushDeleteInfo(common::node_group_idx_t nodeGroupIdx, common::row_idx_t startRow,
    common::row_idx_t numRows, const storage::VersionRecordHandler* versionRecordHandler) const {
    undoBuffer->createDeleteInfo(nodeGroupIdx, startRow, numRows, versionRecordHandler);
}

void Transaction::pushVectorUpdateInfo(storage::UpdateInfo& updateInfo,
    const common::idx_t vectorIdx, storage::VectorUpdateInfo& vectorUpdateInfo,
    common::transaction_t version) const {
    undoBuffer->createVectorUpdateInfo(&updateInfo, vectorIdx, &vectorUpdateInfo, version);
}

Transaction::~Transaction() = default;

common::offset_t Transaction::getMinUncommittedNodeOffset(common::table_id_t tableID) const {
    if (localStorage && localStorage->getLocalTable(tableID)) {
        return localStorage->getLocalTable(tableID)
            ->cast<storage::LocalNodeTable>()
            .getStartOffset();
    }
    return 0;
}

Transaction* Transaction::Get(const main::ClientContext& context) {
    return TransactionContext::Get(context)->getActiveTransaction();
}

Transaction DUMMY_TRANSACTION = Transaction(TransactionType::DUMMY);
Transaction DUMMY_CHECKPOINT_TRANSACTION = Transaction(TransactionType::CHECKPOINT,
    Transaction::DUMMY_TRANSACTION_ID, Transaction::START_TRANSACTION_ID - 1);

} // namespace transaction
} // namespace lbug
