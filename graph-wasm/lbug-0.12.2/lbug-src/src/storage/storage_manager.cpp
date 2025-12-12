#include "storage/storage_manager.h"

#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/file_system/virtual_file_system.h"
#include "common/random_engine.h"
#include "common/serializer/in_mem_file_writer.h"
#include "main/attached_database.h"
#include "main/client_context.h"
#include "main/database.h"
#include "main/db_config.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/checkpointer.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"
#include "storage/wal/wal_replayer.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

StorageManager::StorageManager(const std::string& databasePath, bool readOnly, bool enableChecksums,
    MemoryManager& memoryManager, bool enableCompression, VirtualFileSystem* vfs)
    : databasePath{databasePath}, readOnly{readOnly}, dataFH{nullptr}, memoryManager{memoryManager},
      enableCompression{enableCompression} {
    wal = std::make_unique<WAL>(databasePath, readOnly, enableChecksums, vfs);
    shadowFile =
        std::make_unique<ShadowFile>(*memoryManager.getBufferManager(), vfs, this->databasePath);
    inMemory = main::DBConfig::isDBPathInMemory(databasePath);
    registerIndexType(PrimaryKeyIndex::getIndexType());
}

StorageManager::~StorageManager() = default;

void StorageManager::initDataFileHandle(VirtualFileSystem* vfs, main::ClientContext* context) {
    if (inMemory) {
        dataFH = memoryManager.getBufferManager()->getFileHandle(databasePath,
            FileHandle::O_PERSISTENT_FILE_IN_MEM, vfs, context);
    } else {
        auto flag = readOnly ? FileHandle::O_PERSISTENT_FILE_READ_ONLY :
                               FileHandle::O_PERSISTENT_FILE_CREATE_NOT_EXISTS;
        flag |= FileHandle::O_LOCKED_PERSISTENT_FILE;
        dataFH = memoryManager.getBufferManager()->getFileHandle(databasePath, flag, vfs, context);
        if (dataFH->getNumPages() == 0) {
            if (!readOnly) {
                // Reserve the first page for the database header.
                dataFH->getPageManager()->allocatePage();
                // Write a dummy database header page.
                const auto* initialHeader = getOrInitDatabaseHeader(*context);
                auto headerWriter =
                    std::make_shared<InMemFileWriter>(*MemoryManager::Get(*context));
                Serializer headerSerializer(headerWriter);
                initialHeader->serialize(headerSerializer);
                dataFH->getFileInfo()->writeFile(headerWriter->getPage(0).data(), LBUG_PAGE_SIZE,
                    StorageConstants::DB_HEADER_PAGE_IDX);
                dataFH->getFileInfo()->syncFile();
            }
        }
    }
}

Table* StorageManager::getTable(table_id_t tableID) {
    std::lock_guard lck{mtx};
    KU_ASSERT(tables.contains(tableID));
    return tables.at(tableID).get();
}

void StorageManager::recover(main::ClientContext& clientContext, bool throwOnWalReplayFailure,
    bool enableChecksums) {
    const auto walReplayer = std::make_unique<WALReplayer>(clientContext);
    walReplayer->replay(throwOnWalReplayFailure, enableChecksums);
}

void StorageManager::createNodeTable(NodeTableCatalogEntry* entry) {
    tables[entry->getTableID()] = std::make_unique<NodeTable>(this, entry, &memoryManager);
}

// TODO(Guodong): This API is added since storageManager doesn't provide an API to add a single
// rel table. We may have to refactor the existing StorageManager::createTable(TableCatalogEntry*
// entry).
void StorageManager::addRelTable(RelGroupCatalogEntry* entry, const RelTableCatalogInfo& info) {
    tables[info.oid] = std::make_unique<RelTable>(entry, info.nodePair.srcTableID,
        info.nodePair.dstTableID, this, &memoryManager);
}

void StorageManager::createRelTableGroup(RelGroupCatalogEntry* entry) {
    for (auto& info : entry->getRelEntryInfos()) {
        addRelTable(entry, info);
    }
}

void StorageManager::createTable(TableCatalogEntry* entry) {
    std::lock_guard lck{mtx};
    switch (entry->getType()) {
    case CatalogEntryType::NODE_TABLE_ENTRY: {
        createNodeTable(entry->ptrCast<NodeTableCatalogEntry>());
    } break;
    case CatalogEntryType::REL_GROUP_ENTRY: {
        createRelTableGroup(entry->ptrCast<RelGroupCatalogEntry>());
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

WAL& StorageManager::getWAL() const {
    KU_ASSERT(wal);
    return *wal;
}

ShadowFile& StorageManager::getShadowFile() const {
    KU_ASSERT(shadowFile);
    return *shadowFile;
}

void StorageManager::reclaimDroppedTables(const Catalog& catalog) {
    std::vector<table_id_t> droppedTables;
    for (const auto& [tableID, table] : tables) {
        switch (table->getTableType()) {
        case TableType::NODE: {
            if (!catalog.containsTable(&DUMMY_CHECKPOINT_TRANSACTION, tableID, true)) {
                table->reclaimStorage(*dataFH->getPageManager());
                droppedTables.push_back(tableID);
            }
        } break;
        case TableType::REL: {
            auto& relTable = table->cast<RelTable>();
            auto relGroupID = relTable.getRelGroupID();
            if (!catalog.containsTable(&DUMMY_CHECKPOINT_TRANSACTION, relGroupID, true)) {
                table->reclaimStorage(*dataFH->getPageManager());
                droppedTables.push_back(tableID);
            } else {
                auto relGroupEntry =
                    catalog.getTableCatalogEntry(&DUMMY_CHECKPOINT_TRANSACTION, relGroupID);
                if (!relGroupEntry->cast<RelGroupCatalogEntry>().getRelEntryInfo(
                        relTable.getFromNodeTableID(), relTable.getToNodeTableID())) {
                    table->reclaimStorage(*dataFH->getPageManager());
                    droppedTables.push_back(tableID);
                }
            }
        }
        default: {
            // DO NOTHING.
        }
        }
    }
    for (auto tableID : droppedTables) {
        tables.erase(tableID);
    }
}

bool StorageManager::checkpoint(main::ClientContext* context, PageAllocator& pageAllocator) {
    bool hasChanges = false;
    const auto catalog = Catalog::Get(*context);
    const auto nodeTableEntries = catalog->getNodeTableEntries(&DUMMY_CHECKPOINT_TRANSACTION);
    const auto relGroupEntries = catalog->getRelGroupEntries(&DUMMY_CHECKPOINT_TRANSACTION);

    for (const auto entry : nodeTableEntries) {
        if (!tables.contains(entry->getTableID())) {
            throw RuntimeException(stringFormat(
                "Checkpoint failed: table {} not found in storage manager.", entry->getName()));
        }
        hasChanges =
            tables.at(entry->getTableID())->checkpoint(context, entry, pageAllocator) || hasChanges;
    }
    for (const auto entry : relGroupEntries) {
        for (auto& info : entry->getRelEntryInfos()) {
            if (!tables.contains(info.oid)) {
                throw RuntimeException(stringFormat(
                    "Checkpoint failed: table {} not found in storage manager.", entry->getName()));
            }
            hasChanges =
                tables.at(info.oid)->checkpoint(context, entry, pageAllocator) || hasChanges;
        }
        entry->vacuumColumnIDs(1);
    }
    reclaimDroppedTables(*catalog);
    return hasChanges;
}

void StorageManager::finalizeCheckpoint() {
    dataFH->getPageManager()->finalizeCheckpoint();
}

void StorageManager::rollbackCheckpoint(const Catalog& catalog) {
    std::lock_guard lck{mtx};
    const auto nodeTableEntries = catalog.getNodeTableEntries(&DUMMY_CHECKPOINT_TRANSACTION);
    for (const auto tableEntry : nodeTableEntries) {
        KU_ASSERT(tables.contains(tableEntry->getTableID()));
        tables.at(tableEntry->getTableID())->rollbackCheckpoint();
    }
    dataFH->getPageManager()->rollbackCheckpoint();
}

std::optional<std::reference_wrapper<const IndexType>> StorageManager::getIndexType(
    const std::string& typeName) const {
    for (auto& indexType : registeredIndexTypes) {
        if (StringUtils::caseInsensitiveEquals(indexType.typeName, typeName)) {
            return indexType;
        }
    }
    return std::nullopt;
}

void StorageManager::serialize(const Catalog& catalog, Serializer& ser) {
    std::lock_guard lck{mtx};
    auto nodeTableEntries = catalog.getNodeTableEntries(&DUMMY_CHECKPOINT_TRANSACTION);
    auto relGroupEntries = catalog.getRelGroupEntries(&DUMMY_CHECKPOINT_TRANSACTION);
    std::sort(nodeTableEntries.begin(), nodeTableEntries.end(),
        [](const auto& a, const auto& b) { return a->getTableID() < b->getTableID(); });
    std::sort(relGroupEntries.begin(), relGroupEntries.end(),
        [](const auto& a, const auto& b) { return a->getTableID() < b->getTableID(); });
    ser.writeDebuggingInfo("num_node_tables");
    ser.write<uint64_t>(nodeTableEntries.size());
    for (const auto tableEntry : nodeTableEntries) {
        KU_ASSERT(tables.contains(tableEntry->getTableID()));
        ser.writeDebuggingInfo("table_id");
        ser.write<table_id_t>(tableEntry->getTableID());
        tables.at(tableEntry->getTableID())->serialize(ser);
    }
    ser.writeDebuggingInfo("num_rel_groups");
    ser.write<uint64_t>(relGroupEntries.size());
    for (const auto entry : relGroupEntries) {
        const auto& relGroupEntry = entry->cast<RelGroupCatalogEntry>();
        ser.writeDebuggingInfo("rel_group_id");
        ser.write<table_id_t>(relGroupEntry.getTableID());
        ser.writeDebuggingInfo("num_inner_rel_tables");
        ser.write<uint64_t>(relGroupEntry.getNumRelTables());
        for (auto& info : relGroupEntry.getRelEntryInfos()) {
            KU_ASSERT(tables.contains(info.oid));
            info.serialize(ser);
            tables.at(info.oid)->serialize(ser);
        }
    }
}

void StorageManager::deserialize(main::ClientContext* context, const Catalog* catalog,
    Deserializer& deSer) {
    std::string key;
    deSer.validateDebuggingInfo(key, "num_node_tables");
    uint64_t numNodeTables = 0;
    deSer.deserializeValue<uint64_t>(numNodeTables);
    for (auto i = 0u; i < numNodeTables; i++) {
        deSer.validateDebuggingInfo(key, "table_id");
        table_id_t tableID = INVALID_TABLE_ID;
        deSer.deserializeValue<table_id_t>(tableID);
        if (!catalog->containsTable(&DUMMY_TRANSACTION, tableID)) {
            throw RuntimeException(
                stringFormat("Load table failed: table {} doesn't exist in catalog.", tableID));
        }
        KU_ASSERT(!tables.contains(tableID));
        auto tableEntry = catalog->getTableCatalogEntry(&DUMMY_TRANSACTION, tableID)
                              ->ptrCast<NodeTableCatalogEntry>();
        tables[tableID] = std::make_unique<NodeTable>(this, tableEntry, &memoryManager);
        tables[tableID]->deserialize(context, this, deSer);
    }
    deSer.validateDebuggingInfo(key, "num_rel_groups");
    uint64_t numRelGroups = 0;
    deSer.deserializeValue<uint64_t>(numRelGroups);
    for (auto i = 0u; i < numRelGroups; i++) {
        deSer.validateDebuggingInfo(key, "rel_group_id");
        table_id_t relGroupID = INVALID_TABLE_ID;
        deSer.deserializeValue<table_id_t>(relGroupID);
        if (!catalog->containsTable(&DUMMY_TRANSACTION, relGroupID)) {
            throw RuntimeException(
                stringFormat("Load table failed: table {} doesn't exist in catalog.", relGroupID));
        }
        deSer.validateDebuggingInfo(key, "num_inner_rel_tables");
        uint64_t numInnerRelTables = 0;
        deSer.deserializeValue<uint64_t>(numInnerRelTables);
        auto relGroupEntry = catalog->getTableCatalogEntry(&DUMMY_TRANSACTION, relGroupID)
                                 ->ptrCast<RelGroupCatalogEntry>();
        for (auto k = 0u; k < numInnerRelTables; k++) {
            RelTableCatalogInfo info = RelTableCatalogInfo::deserialize(deSer);
            KU_ASSERT(!tables.contains(info.oid));
            tables[info.oid] = std::make_unique<RelTable>(relGroupEntry, info.nodePair.srcTableID,
                info.nodePair.dstTableID, this, &memoryManager);
            tables.at(info.oid)->deserialize(context, this, deSer);
        }
    }
}

common::ku_uuid_t StorageManager::getOrInitDatabaseID(const main::ClientContext& clientContext) {
    return getOrInitDatabaseHeader(clientContext)->databaseID;
}

const storage::DatabaseHeader* StorageManager::getOrInitDatabaseHeader(
    const main::ClientContext& clientContext) {
    if (databaseHeader == nullptr) {
        // We should only create the database header if a persistent one doesn't exist
        KU_ASSERT(std::nullopt == DatabaseHeader::readDatabaseHeader(*dataFH->getFileInfo()));
        databaseHeader = std::make_unique<DatabaseHeader>(
            DatabaseHeader::createInitialHeader(RandomEngine::Get(clientContext)));
    }
    return databaseHeader.get();
}

void StorageManager::setDatabaseHeader(std::unique_ptr<storage::DatabaseHeader> header) {
    KU_ASSERT(!databaseHeader || header->databaseID.value == databaseHeader->databaseID.value);
    databaseHeader = std::move(header);
}

StorageManager* StorageManager::Get(const main::ClientContext& context) {
    if (context.getAttachedDatabase()) {
        return context.getAttachedDatabase()->getStorageManager();
    } else {
        return context.getDatabase()->getStorageManager();
    }
}

} // namespace storage
} // namespace lbug
