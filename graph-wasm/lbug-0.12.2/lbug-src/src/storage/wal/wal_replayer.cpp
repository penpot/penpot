#include "storage/wal/wal_replayer.h"

#include "binder/binder.h"
#include "catalog/catalog_entry/scalar_macro_catalog_entry.h"
#include "catalog/catalog_entry/sequence_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "catalog/catalog_entry/type_catalog_entry.h"
#include "common/file_system/file_info.h"
#include "common/file_system/file_system.h"
#include "common/file_system/virtual_file_system.h"
#include "common/serializer/buffered_file.h"
#include "extension/extension_manager.h"
#include "main/client_context.h"
#include "processor/expression_mapper.h"
#include "storage/file_db_id_utils.h"
#include "storage/local_storage/local_rel_table.h"
#include "storage/storage_manager.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"
#include "storage/wal/checksum_reader.h"
#include "storage/wal/wal_record.h"
#include "transaction/transaction_context.h"

using namespace lbug::binder;
using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::processor;
using namespace lbug::storage;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

static constexpr std::string_view checksumMismatchMessage =
    "Checksum verification failed, the WAL file is corrupted.";

WALReplayer::WALReplayer(main::ClientContext& clientContext) : clientContext{clientContext} {
    walPath = StorageUtils::getWALFilePath(clientContext.getDatabasePath());
    shadowFilePath = StorageUtils::getShadowFilePath(clientContext.getDatabasePath());
}

static WALHeader readWALHeader(Deserializer& deserializer) {
    WALHeader header{};
    deserializer.deserializeValue(header.databaseID);

    // It is possible to read a value other than 0/1 when deserializing the flag
    // This causes some weird behaviours with some toolchains so we manually do the conversion here
    uint8_t enableChecksumsBytes = 0;
    deserializer.deserializeValue(enableChecksumsBytes);
    header.enableChecksums = enableChecksumsBytes != 0;

    return header;
}

static Deserializer initDeserializer(FileInfo& fileInfo, main::ClientContext& clientContext,
    bool enableChecksums) {
    if (enableChecksums) {
        return Deserializer{std::make_unique<ChecksumReader>(fileInfo,
            *MemoryManager::Get(clientContext), checksumMismatchMessage)};
    } else {
        return Deserializer{std::make_unique<BufferedFileReader>(fileInfo)};
    }
}

static void checkWALHeader(const WALHeader& header, bool enableChecksums) {
    if (enableChecksums != header.enableChecksums) {
        throw RuntimeException(stringFormat(
            "The database you are trying to open was serialized with enableChecksums={} but you "
            "are trying to open it with enableChecksums={}. Please open your database using the "
            "correct enableChecksums config. If you wish to change this for your database, please "
            "use the export/import functionality.",
            TypeUtils::toString(header.enableChecksums), TypeUtils::toString(enableChecksums)));
    }
}

static uint64_t getReadOffset(Deserializer& deSer, bool enableChecksums) {
    if (enableChecksums) {
        return deSer.getReader()->cast<ChecksumReader>()->getReadOffset();
    } else {
        return deSer.getReader()->cast<BufferedFileReader>()->getReadOffset();
    }
}

void WALReplayer::replay(bool throwOnWalReplayFailure, bool enableChecksums) const {
    auto vfs = VirtualFileSystem::GetUnsafe(clientContext);
    Checkpointer checkpointer(clientContext);
    // First, check if the WAL file exists. If it does not, we can safely remove the shadow file.
    if (!vfs->fileOrPathExists(walPath, &clientContext)) {
        removeFileIfExists(shadowFilePath);
        // Read the checkpointed data from the disk.
        checkpointer.readCheckpoint();
        return;
    }
    // If the WAL file exists, we need to replay it.
    auto fileInfo = openWALFile();
    // Check if the wal file is empty. If so, we do not need to replay anything.
    if (fileInfo->getFileSize() == 0) {
        removeWALAndShadowFiles();
        // Read the checkpointed data from the disk.
        checkpointer.readCheckpoint();
        return;
    }
    // A previous unclean exit may have left non-durable contents in the WAL, so before we start
    // replaying the WAL records, make a best-effort attempt at ensuring the WAL is fully durable.
    syncWALFile(*fileInfo);

    // Start replaying the WAL records.
    try {
        // First, we dry run the replay to find out the offset of the last record that was
        // CHECKPOINT or COMMIT.
        auto [offsetDeserialized, isLastRecordCheckpoint] =
            dryReplay(*fileInfo, throwOnWalReplayFailure, enableChecksums);
        if (isLastRecordCheckpoint) {
            // If the last record is a checkpoint, we resume by replaying the shadow file.
            ShadowFile::replayShadowPageRecords(clientContext);
            removeWALAndShadowFiles();
            // Re-read checkpointed data from disk again as now the shadow file is applied.
            checkpointer.readCheckpoint();
        } else {
            // There is no checkpoint record, so we should remove the shadow file if it exists.
            removeFileIfExists(shadowFilePath);
            // Read the checkpointed data from the disk.
            checkpointer.readCheckpoint();
            // Resume by replaying the WAL file from the beginning until the last COMMIT record.
            Deserializer deserializer = initDeserializer(*fileInfo, clientContext, enableChecksums);

            if (offsetDeserialized > 0) {
                // Make sure the WAL file is for the current database
                deserializer.getReader()->onObjectBegin();
                const auto walHeader = readWALHeader(deserializer);
                FileDBIDUtils::verifyDatabaseID(*fileInfo,
                    StorageManager::Get(clientContext)->getOrInitDatabaseID(clientContext),
                    walHeader.databaseID);
                deserializer.getReader()->onObjectEnd();
            }

            while (getReadOffset(deserializer, enableChecksums) < offsetDeserialized) {
                KU_ASSERT(!deserializer.finished());
                auto walRecord = WALRecord::deserialize(deserializer, clientContext);
                replayWALRecord(*walRecord);
            }
            // After replaying all the records, we should truncate the WAL file to the last
            // COMMIT/CHECKPOINT record.
            truncateWALFile(*fileInfo, offsetDeserialized);
        }
    } catch (const std::exception&) {
        auto transactionContext = TransactionContext::Get(clientContext);
        if (transactionContext->hasActiveTransaction()) {
            // Handle the case that some transaction went during replaying. We should roll back
            // under this case. Usually this shouldn't happen, but it is possible if we have a bug
            // with the replay logic. This is to handle cases like that so we don't corrupt
            // transactions that have been replayed.
            transactionContext->rollback();
        }
        throw;
    }
}

WALReplayer::WALReplayInfo WALReplayer::dryReplay(FileInfo& fileInfo, bool throwOnWalReplayFailure,
    bool enableChecksums) const {
    uint64_t offsetDeserialized = 0;
    bool isLastRecordCheckpoint = false;
    try {
        Deserializer deserializer = initDeserializer(fileInfo, clientContext, enableChecksums);

        // Skip the databaseID here, we'll verify it when we actually replay
        deserializer.getReader()->onObjectBegin();
        const auto walHeader = readWALHeader(deserializer);
        checkWALHeader(walHeader, enableChecksums);
        deserializer.getReader()->onObjectEnd();

        bool finishedDeserializing = deserializer.finished();
        while (!finishedDeserializing) {
            auto walRecord = WALRecord::deserialize(deserializer, clientContext);
            finishedDeserializing = deserializer.finished();
            switch (walRecord->type) {
            case WALRecordType::CHECKPOINT_RECORD: {
                KU_ASSERT(finishedDeserializing);
                // If we reach a checkpoint record, we can stop replaying.
                isLastRecordCheckpoint = true;
                finishedDeserializing = true;
                offsetDeserialized = getReadOffset(deserializer, enableChecksums);
            } break;
            case WALRecordType::COMMIT_RECORD: {
                // Update the offset to the end of the last commit record.
                offsetDeserialized = getReadOffset(deserializer, enableChecksums);
            } break;
            default: {
                // DO NOTHING.
            }
            }
        }
    } catch (...) {
        // If we hit an exception while deserializing, we assume that the WAL file is (partially)
        // corrupted. This should only happen for records of the last transaction recorded.
        if (throwOnWalReplayFailure) {
            throw;
        }
    }
    return {offsetDeserialized, isLastRecordCheckpoint};
}

void WALReplayer::replayWALRecord(WALRecord& walRecord) const {
    switch (walRecord.type) {
    case WALRecordType::BEGIN_TRANSACTION_RECORD: {
        TransactionContext::Get(clientContext)->beginRecoveryTransaction();
    } break;
    case WALRecordType::COMMIT_RECORD: {
        TransactionContext::Get(clientContext)->commit();
    } break;
    case WALRecordType::CREATE_CATALOG_ENTRY_RECORD: {
        replayCreateCatalogEntryRecord(walRecord);
    } break;
    case WALRecordType::DROP_CATALOG_ENTRY_RECORD: {
        replayDropCatalogEntryRecord(walRecord);
    } break;
    case WALRecordType::ALTER_TABLE_ENTRY_RECORD: {
        replayAlterTableEntryRecord(walRecord);
    } break;
    case WALRecordType::TABLE_INSERTION_RECORD: {
        replayTableInsertionRecord(walRecord);
    } break;
    case WALRecordType::NODE_DELETION_RECORD: {
        replayNodeDeletionRecord(walRecord);
    } break;
    case WALRecordType::NODE_UPDATE_RECORD: {
        replayNodeUpdateRecord(walRecord);
    } break;
    case WALRecordType::REL_DELETION_RECORD: {
        replayRelDeletionRecord(walRecord);
    } break;
    case WALRecordType::REL_DETACH_DELETE_RECORD: {
        replayRelDetachDeletionRecord(walRecord);
    } break;
    case WALRecordType::REL_UPDATE_RECORD: {
        replayRelUpdateRecord(walRecord);
    } break;
    case WALRecordType::COPY_TABLE_RECORD: {
        replayCopyTableRecord(walRecord);
    } break;
    case WALRecordType::UPDATE_SEQUENCE_RECORD: {
        replayUpdateSequenceRecord(walRecord);
    } break;
    case WALRecordType::LOAD_EXTENSION_RECORD: {
        replayLoadExtensionRecord(walRecord);
    } break;
    case WALRecordType::CHECKPOINT_RECORD: {
        // This record should not be replayed. It is only used to indicate that the previous records
        // had been replayed and shadow files are created.
        KU_UNREACHABLE;
    }
    default:
        KU_UNREACHABLE;
    }
}

void WALReplayer::replayCreateCatalogEntryRecord(WALRecord& walRecord) const {
    auto catalog = Catalog::Get(clientContext);
    auto transaction = transaction::Transaction::Get(clientContext);
    auto storageManager = StorageManager::Get(clientContext);
    auto& record = walRecord.cast<CreateCatalogEntryRecord>();
    switch (record.ownedCatalogEntry->getType()) {
    case CatalogEntryType::NODE_TABLE_ENTRY:
    case CatalogEntryType::REL_GROUP_ENTRY: {
        auto& entry = record.ownedCatalogEntry->constCast<TableCatalogEntry>();
        auto newEntry = catalog->createTableEntry(transaction,
            entry.getBoundCreateTableInfo(transaction, record.isInternal));
        storageManager->createTable(newEntry->ptrCast<TableCatalogEntry>());
    } break;
    case CatalogEntryType::SCALAR_MACRO_ENTRY: {
        auto& macroEntry = record.ownedCatalogEntry->constCast<ScalarMacroCatalogEntry>();
        catalog->addScalarMacroFunction(transaction, macroEntry.getName(),
            macroEntry.getMacroFunction()->copy());
    } break;
    case CatalogEntryType::SEQUENCE_ENTRY: {
        auto& sequenceEntry = record.ownedCatalogEntry->constCast<SequenceCatalogEntry>();
        catalog->createSequence(transaction,
            sequenceEntry.getBoundCreateSequenceInfo(record.isInternal));
    } break;
    case CatalogEntryType::TYPE_ENTRY: {
        auto& typeEntry = record.ownedCatalogEntry->constCast<TypeCatalogEntry>();
        catalog->createType(transaction, typeEntry.getName(), typeEntry.getLogicalType().copy());
    } break;
    case CatalogEntryType::INDEX_ENTRY: {
        catalog->createIndex(transaction, std::move(record.ownedCatalogEntry));
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

void WALReplayer::replayDropCatalogEntryRecord(const WALRecord& walRecord) const {
    auto& dropEntryRecord = walRecord.constCast<DropCatalogEntryRecord>();
    auto catalog = Catalog::Get(clientContext);
    auto transaction = transaction::Transaction::Get(clientContext);
    const auto entryID = dropEntryRecord.entryID;
    switch (dropEntryRecord.entryType) {
    case CatalogEntryType::NODE_TABLE_ENTRY:
    case CatalogEntryType::REL_GROUP_ENTRY: {
        KU_ASSERT(Catalog::Get(clientContext));
        catalog->dropTableEntry(transaction, entryID);
    } break;
    case CatalogEntryType::SEQUENCE_ENTRY: {
        catalog->dropSequence(transaction, entryID);
    } break;
    case CatalogEntryType::INDEX_ENTRY: {
        catalog->dropIndex(transaction, entryID);
    } break;
    case CatalogEntryType::SCALAR_MACRO_ENTRY: {
        catalog->dropMacroEntry(transaction, entryID);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

void WALReplayer::replayAlterTableEntryRecord(const WALRecord& walRecord) const {
    auto binder = Binder(&clientContext);
    auto& alterEntryRecord = walRecord.constCast<AlterTableEntryRecord>();
    auto catalog = Catalog::Get(clientContext);
    auto transaction = transaction::Transaction::Get(clientContext);
    auto storageManager = StorageManager::Get(clientContext);
    auto ownedAlterInfo = alterEntryRecord.ownedAlterInfo.get();
    catalog->alterTableEntry(transaction, *ownedAlterInfo);
    auto& pageAllocator = *PageManager::Get(clientContext);
    switch (ownedAlterInfo->alterType) {
    case AlterType::ADD_PROPERTY: {
        const auto exprBinder = binder.getExpressionBinder();
        const auto addInfo = ownedAlterInfo->extraInfo->constPtrCast<BoundExtraAddPropertyInfo>();
        // We don't implicit cast here since it must already be done the first time
        const auto boundDefault =
            exprBinder->bindExpression(*addInfo->propertyDefinition.defaultExpr);
        auto exprMapper = ExpressionMapper();
        const auto defaultValueEvaluator = exprMapper.getEvaluator(boundDefault);
        defaultValueEvaluator->init(ResultSet(0) /* dummy ResultSet */, &clientContext);
        const auto entry = catalog->getTableCatalogEntry(transaction, ownedAlterInfo->tableName);
        const auto& addedProp = entry->getProperty(addInfo->propertyDefinition.getName());
        TableAddColumnState state{addedProp, *defaultValueEvaluator};
        KU_ASSERT(StorageManager::Get(clientContext));
        switch (entry->getTableType()) {
        case TableType::REL: {
            for (auto& relEntryInfo : entry->cast<RelGroupCatalogEntry>().getRelEntryInfos()) {
                storageManager->getTable(relEntryInfo.oid)
                    ->addColumn(transaction, state, pageAllocator);
            }
        } break;
        case TableType::NODE: {
            storageManager->getTable(entry->getTableID())
                ->addColumn(transaction, state, pageAllocator);
        } break;
        default: {
            KU_UNREACHABLE;
        }
        }
    } break;
    case AlterType::ADD_FROM_TO_CONNECTION: {
        auto extraInfo = ownedAlterInfo->extraInfo->constPtrCast<BoundExtraAlterFromToConnection>();
        auto relGroupEntry = catalog->getTableCatalogEntry(transaction, ownedAlterInfo->tableName)
                                 ->ptrCast<RelGroupCatalogEntry>();
        auto relEntryInfo =
            relGroupEntry->getRelEntryInfo(extraInfo->fromTableID, extraInfo->toTableID);
        storageManager->addRelTable(relGroupEntry, *relEntryInfo);
    } break;
    default:
        break;
    }
}

void WALReplayer::replayTableInsertionRecord(const WALRecord& walRecord) const {
    const auto& insertionRecord = walRecord.constCast<TableInsertionRecord>();
    switch (insertionRecord.tableType) {
    case TableType::NODE: {
        replayNodeTableInsertRecord(walRecord);
    } break;
    case TableType::REL: {
        replayRelTableInsertRecord(walRecord);
    } break;
    default: {
        throw RuntimeException("Invalid table type for insertion replay in WAL record.");
    }
    }
}

void WALReplayer::replayNodeTableInsertRecord(const WALRecord& walRecord) const {
    const auto& insertionRecord = walRecord.constCast<TableInsertionRecord>();
    const auto tableID = insertionRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<NodeTable>();
    KU_ASSERT(!insertionRecord.ownedVectors.empty());
    const auto anchorState = insertionRecord.ownedVectors[0]->state;
    const auto numNodes = anchorState->getSelVector().getSelSize();
    for (auto i = 0u; i < insertionRecord.ownedVectors.size(); i++) {
        insertionRecord.ownedVectors[i]->setState(anchorState);
    }
    std::vector<ValueVector*> propertyVectors(insertionRecord.ownedVectors.size());
    for (auto i = 0u; i < insertionRecord.ownedVectors.size(); i++) {
        propertyVectors[i] = insertionRecord.ownedVectors[i].get();
    }
    KU_ASSERT(table.getPKColumnID() < insertionRecord.ownedVectors.size());
    auto& pkVector = *insertionRecord.ownedVectors[table.getPKColumnID()];
    const auto nodeIDVector = std::make_unique<ValueVector>(LogicalType::INTERNAL_ID());
    nodeIDVector->setState(anchorState);
    const auto insertState =
        std::make_unique<NodeTableInsertState>(*nodeIDVector, pkVector, propertyVectors);
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    table.initInsertState(&clientContext, *insertState);
    anchorState->getSelVectorUnsafe().setToFiltered(1);
    for (auto i = 0u; i < numNodes; i++) {
        anchorState->getSelVectorUnsafe()[0] = i;
        table.insert(transaction::Transaction::Get(clientContext), *insertState);
    }
}

void WALReplayer::replayRelTableInsertRecord(const WALRecord& walRecord) const {
    const auto& insertionRecord = walRecord.constCast<TableInsertionRecord>();
    const auto tableID = insertionRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<RelTable>();
    KU_ASSERT(!insertionRecord.ownedVectors.empty());
    const auto anchorState = insertionRecord.ownedVectors[0]->state;
    const auto numRels = anchorState->getSelVector().getSelSize();
    anchorState->getSelVectorUnsafe().setToFiltered(1);
    for (auto i = 0u; i < insertionRecord.ownedVectors.size(); i++) {
        insertionRecord.ownedVectors[i]->setState(anchorState);
    }
    std::vector<ValueVector*> propertyVectors;
    for (auto i = 0u; i < insertionRecord.ownedVectors.size(); i++) {
        if (i < LOCAL_REL_ID_COLUMN_ID) {
            // Skip the first two vectors which are the src nodeID and the dst nodeID.
            continue;
        }
        propertyVectors.push_back(insertionRecord.ownedVectors[i].get());
    }
    const auto insertState = std::make_unique<RelTableInsertState>(
        *insertionRecord.ownedVectors[LOCAL_BOUND_NODE_ID_COLUMN_ID],
        *insertionRecord.ownedVectors[LOCAL_NBR_NODE_ID_COLUMN_ID], propertyVectors);
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    for (auto i = 0u; i < numRels; i++) {
        anchorState->getSelVectorUnsafe()[0] = i;
        table.initInsertState(&clientContext, *insertState);
        table.insert(transaction::Transaction::Get(clientContext), *insertState);
    }
}

void WALReplayer::replayNodeDeletionRecord(const WALRecord& walRecord) const {
    const auto& deletionRecord = walRecord.constCast<NodeDeletionRecord>();
    const auto tableID = deletionRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<NodeTable>();
    const auto anchorState = deletionRecord.ownedPKVector->state;
    KU_ASSERT(anchorState->getSelVector().getSelSize() == 1);
    const auto nodeIDVector = std::make_unique<ValueVector>(LogicalType::INTERNAL_ID());
    nodeIDVector->setState(anchorState);
    nodeIDVector->setValue<internalID_t>(0,
        internalID_t{deletionRecord.nodeOffset, deletionRecord.tableID});
    const auto deleteState =
        std::make_unique<NodeTableDeleteState>(*nodeIDVector, *deletionRecord.ownedPKVector);
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    table.delete_(transaction::Transaction::Get(clientContext), *deleteState);
}

void WALReplayer::replayNodeUpdateRecord(const WALRecord& walRecord) const {
    const auto& updateRecord = walRecord.constCast<NodeUpdateRecord>();
    const auto tableID = updateRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<NodeTable>();
    const auto anchorState = updateRecord.ownedPropertyVector->state;
    KU_ASSERT(anchorState->getSelVector().getSelSize() == 1);
    const auto nodeIDVector = std::make_unique<ValueVector>(LogicalType::INTERNAL_ID());
    nodeIDVector->setState(anchorState);
    nodeIDVector->setValue<internalID_t>(0,
        internalID_t{updateRecord.nodeOffset, updateRecord.tableID});
    const auto updateState = std::make_unique<NodeTableUpdateState>(updateRecord.columnID,
        *nodeIDVector, *updateRecord.ownedPropertyVector);
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    table.update(transaction::Transaction::Get(clientContext), *updateState);
}

void WALReplayer::replayRelDeletionRecord(const WALRecord& walRecord) const {
    const auto& deletionRecord = walRecord.constCast<RelDeletionRecord>();
    const auto tableID = deletionRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<RelTable>();
    const auto anchorState = deletionRecord.ownedRelIDVector->state;
    KU_ASSERT(anchorState->getSelVector().getSelSize() == 1);
    const auto deleteState =
        std::make_unique<RelTableDeleteState>(*deletionRecord.ownedSrcNodeIDVector,
            *deletionRecord.ownedDstNodeIDVector, *deletionRecord.ownedRelIDVector);
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    table.delete_(transaction::Transaction::Get(clientContext), *deleteState);
}

void WALReplayer::replayRelDetachDeletionRecord(const WALRecord& walRecord) const {
    const auto& deletionRecord = walRecord.constCast<RelDetachDeleteRecord>();
    const auto tableID = deletionRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<RelTable>();
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    const auto anchorState = deletionRecord.ownedSrcNodeIDVector->state;
    KU_ASSERT(anchorState->getSelVector().getSelSize() == 1);
    const auto dstNodeIDVector =
        std::make_unique<ValueVector>(LogicalType{LogicalTypeID::INTERNAL_ID});
    const auto relIDVector = std::make_unique<ValueVector>(LogicalType{LogicalTypeID::INTERNAL_ID});
    dstNodeIDVector->setState(anchorState);
    relIDVector->setState(anchorState);
    const auto deleteState = std::make_unique<RelTableDeleteState>(
        *deletionRecord.ownedSrcNodeIDVector, *dstNodeIDVector, *relIDVector);
    deleteState->detachDeleteDirection = deletionRecord.direction;
    table.detachDelete(transaction::Transaction::Get(clientContext), deleteState.get());
}

void WALReplayer::replayRelUpdateRecord(const WALRecord& walRecord) const {
    const auto& updateRecord = walRecord.constCast<RelUpdateRecord>();
    const auto tableID = updateRecord.tableID;
    auto& table = StorageManager::Get(clientContext)->getTable(tableID)->cast<RelTable>();
    const auto anchorState = updateRecord.ownedRelIDVector->state;
    KU_ASSERT(anchorState == updateRecord.ownedSrcNodeIDVector->state &&
              anchorState == updateRecord.ownedSrcNodeIDVector->state &&
              anchorState == updateRecord.ownedPropertyVector->state);
    KU_ASSERT(anchorState->getSelVector().getSelSize() == 1);
    const auto updateState = std::make_unique<RelTableUpdateState>(updateRecord.columnID,
        *updateRecord.ownedSrcNodeIDVector, *updateRecord.ownedDstNodeIDVector,
        *updateRecord.ownedRelIDVector, *updateRecord.ownedPropertyVector);
    KU_ASSERT(transaction::Transaction::Get(clientContext) &&
              transaction::Transaction::Get(clientContext)->isRecovery());
    table.update(transaction::Transaction::Get(clientContext), *updateState);
}

void WALReplayer::replayCopyTableRecord(const WALRecord&) const {
    // DO NOTHING.
}

void WALReplayer::replayUpdateSequenceRecord(const WALRecord& walRecord) const {
    auto& sequenceEntryRecord = walRecord.constCast<UpdateSequenceRecord>();
    const auto sequenceID = sequenceEntryRecord.sequenceID;
    const auto entry =
        Catalog::Get(clientContext)
            ->getSequenceEntry(transaction::Transaction::Get(clientContext), sequenceID);
    entry->nextKVal(transaction::Transaction::Get(clientContext), sequenceEntryRecord.kCount);
}

void WALReplayer::replayLoadExtensionRecord(const WALRecord& walRecord) const {
    const auto& loadExtensionRecord = walRecord.constCast<LoadExtensionRecord>();
    extension::ExtensionManager::Get(clientContext)
        ->loadExtension(loadExtensionRecord.path, &clientContext);
}

void WALReplayer::removeWALAndShadowFiles() const {
    removeFileIfExists(shadowFilePath);
    removeFileIfExists(walPath);
}

void WALReplayer::removeFileIfExists(const std::string& path) const {
    if (StorageManager::Get(clientContext)->isReadOnly()) {
        return;
    }
    auto vfs = VirtualFileSystem::GetUnsafe(clientContext);
    if (vfs->fileOrPathExists(path, &clientContext)) {
        vfs->removeFileIfExists(path);
    }
}

std::unique_ptr<FileInfo> WALReplayer::openWALFile() const {
    auto flag = FileFlags::READ_ONLY;
    if (!StorageManager::Get(clientContext)->isReadOnly()) {
        flag |= FileFlags::WRITE; // The write flag here is to ensure the file is opened with O_RDWR
                                  // so that we can sync it.
    }
    return VirtualFileSystem::GetUnsafe(clientContext)->openFile(walPath, FileOpenFlags(flag));
}

void WALReplayer::syncWALFile(const FileInfo& fileInfo) const {
    if (StorageManager::Get(clientContext)->isReadOnly()) {
        return;
    }
    fileInfo.syncFile();
}

void WALReplayer::truncateWALFile(FileInfo& fileInfo, uint64_t size) const {
    if (StorageManager::Get(clientContext)->isReadOnly()) {
        return;
    }
    if (fileInfo.getFileSize() > size) {
        fileInfo.truncate(size);
        fileInfo.syncFile();
    }
}

} // namespace storage
} // namespace lbug
