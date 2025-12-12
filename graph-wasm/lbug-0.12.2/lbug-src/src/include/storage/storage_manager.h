#pragma once

#include <mutex>

#include "catalog/catalog.h"
#include "shadow_file.h"
#include "storage/index/index.h"
#include "storage/wal/wal.h"

namespace lbug {
namespace main {
class Database;
} // namespace main

namespace catalog {
class CatalogEntry;
class NodeTableCatalogEntry;
class RelGroupCatalogEntry;
struct RelTableCatalogInfo;
} // namespace catalog

namespace storage {
class Table;
class NodeTable;
class RelTable;
class DiskArrayCollection;
struct DatabaseHeader;

class LBUG_API StorageManager {
public:
    StorageManager(const std::string& databasePath, bool readOnly, bool enableChecksums,
        MemoryManager& memoryManager, bool enableCompression, common::VirtualFileSystem* vfs);
    ~StorageManager();

    Table* getTable(common::table_id_t tableID);

    static void recover(main::ClientContext& clientContext, bool throwOnWalReplayFailure,
        bool enableChecksums);

    void createTable(catalog::TableCatalogEntry* entry);
    void addRelTable(catalog::RelGroupCatalogEntry* entry,
        const catalog::RelTableCatalogInfo& info);

    bool checkpoint(main::ClientContext* context, PageAllocator& pageAllocator);
    void finalizeCheckpoint();
    void rollbackCheckpoint(const catalog::Catalog& catalog);

    WAL& getWAL() const;
    ShadowFile& getShadowFile() const;
    FileHandle* getDataFH() const { return dataFH; }
    std::string getDatabasePath() const { return databasePath; }
    bool isReadOnly() const { return readOnly; }
    bool compressionEnabled() const { return enableCompression; }
    bool isInMemory() const { return inMemory; }

    void registerIndexType(IndexType indexType) {
        registeredIndexTypes.push_back(std::move(indexType));
    }
    std::optional<std::reference_wrapper<const IndexType>> getIndexType(
        const std::string& typeName) const;

    void serialize(const catalog::Catalog& catalog, common::Serializer& ser);
    // We need to pass in the catalog and storageManager explicitly as they can be from
    // attachedDatabase.
    void deserialize(main::ClientContext* context, const catalog::Catalog* catalog,
        common::Deserializer& deSer);

    void initDataFileHandle(common::VirtualFileSystem* vfs, main::ClientContext* context);

    // If the database header hasn't been created yet, calling these methods will create + return
    // the header
    common::ku_uuid_t getOrInitDatabaseID(const main::ClientContext& clientContext);
    const storage::DatabaseHeader* getOrInitDatabaseHeader(
        const main::ClientContext& clientContext);

    void setDatabaseHeader(std::unique_ptr<storage::DatabaseHeader> header);

    static StorageManager* Get(const main::ClientContext& context);

private:
    void createNodeTable(catalog::NodeTableCatalogEntry* entry);

    void createRelTableGroup(catalog::RelGroupCatalogEntry* entry);

    void reclaimDroppedTables(const catalog::Catalog& catalog);

private:
    std::mutex mtx;
    std::string databasePath;
    std::unique_ptr<storage::DatabaseHeader> databaseHeader;
    bool readOnly;
    FileHandle* dataFH;
    std::unordered_map<common::table_id_t, std::unique_ptr<Table>> tables;
    MemoryManager& memoryManager;
    std::unique_ptr<WAL> wal;
    std::unique_ptr<ShadowFile> shadowFile;
    bool enableCompression;
    bool inMemory;
    std::vector<IndexType> registeredIndexTypes;
};

} // namespace storage
} // namespace lbug
