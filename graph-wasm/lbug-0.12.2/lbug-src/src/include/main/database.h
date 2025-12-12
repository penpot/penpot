#pragma once

#include <memory>
#include <mutex>
#include <vector>

#if defined(__APPLE__)
#include <pthread/qos.h>
#endif

#include "common/api.h"
#include "common/database_lifecycle_manager.h"
#include "lbug_fwd.h"
#include "main/db_config.h"

namespace lbug {
namespace common {
class FileSystem;
} // namespace common

namespace extension {
class ExtensionManager;
class TransformerExtension;
class BinderExtension;
class PlannerExtension;
class MapperExtension;
} // namespace extension

namespace storage {
class StorageExtension;
} // namespace storage

namespace main {
class DatabaseManager;
/**
 * @brief Stores runtime configuration for creating or opening a Database
 */
struct LBUG_API SystemConfig {
    /**
     * @brief Creates a SystemConfig object.
     * @param bufferPoolSize Max size of the buffer pool in bytes.
     *        The larger the buffer pool, the more data from the database files is kept in memory,
     *        reducing the amount of File I/O
     * @param maxNumThreads The maximum number of threads to use during query execution
     * @param enableCompression Whether or not to compress data on-disk for supported types
     * @param readOnly If true, the database is opened read-only. No write transaction is
     * allowed on the `Database` object. Multiple read-only `Database` objects can be created with
     * the same database path. If false, the database is opened read-write. Under this mode,
     * there must not be multiple `Database` objects created with the same database path.
     * @param maxDBSize The maximum size of the database in bytes. Note that this is introduced
     * temporarily for now to get around with the default 8TB mmap address space limit some
     * environment. This will be removed once we implemente a better solution later. The value is
     * default to 1 << 43 (8TB) under 64-bit environment and 1GB under 32-bit one (see
     * `DEFAULT_VM_REGION_MAX_SIZE`).
     * @param autoCheckpoint If true, the database will automatically checkpoint when the size of
     * the WAL file exceeds the checkpoint threshold.
     * @param checkpointThreshold The threshold of the WAL file size in bytes. When the size of the
     * WAL file exceeds this threshold, the database will checkpoint if autoCheckpoint is true.
     * @param forceCheckpointOnClose If true, the database will force checkpoint when closing.
     * @param throwOnWalReplayFailure If true, any WAL replaying failure when loading the database
     * will throw an error. Otherwise, Lbug will silently ignore the failure and replay up to where
     * the error occured.
     * @param enableChecksums If true, the database will use checksums to detect corruption in the
     * WAL file.
     */
    explicit SystemConfig(uint64_t bufferPoolSize = -1u, uint64_t maxNumThreads = 0,
        bool enableCompression = true, bool readOnly = false, uint64_t maxDBSize = -1u,
        bool autoCheckpoint = true, uint64_t checkpointThreshold = 16777216 /* 16MB */,
        bool forceCheckpointOnClose = true, bool throwOnWalReplayFailure = true,
        bool enableChecksums = true
#if defined(__APPLE__)
        ,
        uint32_t threadQos = QOS_CLASS_DEFAULT
#endif
    );

    uint64_t bufferPoolSize;
    uint64_t maxNumThreads;
    bool enableCompression;
    bool readOnly;
    uint64_t maxDBSize;
    bool autoCheckpoint;
    uint64_t checkpointThreshold;
    bool forceCheckpointOnClose;
    bool throwOnWalReplayFailure;
    bool enableChecksums;
#if defined(__APPLE__)
    uint32_t threadQos;
#endif
};

/**
 * @brief Database class is the main class of Lbug. It manages all database components.
 */
class Database {
    friend class EmbeddedShell;
    friend class ClientContext;
    friend class Connection;
    friend class testing::BaseGraphTest;

public:
    /**
     * @brief Creates a database object.
     * @param databasePath Database path. If left empty, or :memory: is specified, this will create
     *        an in-memory database.
     * @param systemConfig System configurations (buffer pool size and max num threads).
     */
    LBUG_API explicit Database(std::string_view databasePath,
        SystemConfig systemConfig = SystemConfig());
    /**
     * @brief Destructs the database object.
     */
    LBUG_API ~Database();

    LBUG_API void registerFileSystem(std::unique_ptr<common::FileSystem> fs);

    LBUG_API void registerStorageExtension(std::string name,
        std::unique_ptr<storage::StorageExtension> storageExtension);

    LBUG_API void addExtensionOption(std::string name, common::LogicalTypeID type,
        common::Value defaultValue, bool isConfidential = false);

    LBUG_API void addTransformerExtension(
        std::unique_ptr<extension::TransformerExtension> transformerExtension);

    std::vector<extension::TransformerExtension*> getTransformerExtensions();

    LBUG_API void addBinderExtension(
        std::unique_ptr<extension::BinderExtension> transformerExtension);

    std::vector<extension::BinderExtension*> getBinderExtensions();

    LBUG_API void addPlannerExtension(
        std::unique_ptr<extension::PlannerExtension> plannerExtension);

    std::vector<extension::PlannerExtension*> getPlannerExtensions();

    LBUG_API void addMapperExtension(std::unique_ptr<extension::MapperExtension> mapperExtension);

    std::vector<extension::MapperExtension*> getMapperExtensions();

    catalog::Catalog* getCatalog() { return catalog.get(); }

    const DBConfig& getConfig() const { return dbConfig; }

    std::vector<storage::StorageExtension*> getStorageExtensions();

    uint64_t getNextQueryID();

    storage::StorageManager* getStorageManager() { return storageManager.get(); }

    transaction::TransactionManager* getTransactionManager() { return transactionManager.get(); }

    DatabaseManager* getDatabaseManager() { return databaseManager.get(); }

    storage::MemoryManager* getMemoryManager() { return memoryManager.get(); }

    processor::QueryProcessor* getQueryProcessor() { return queryProcessor.get(); }

    extension::ExtensionManager* getExtensionManager() { return extensionManager.get(); }

    common::VirtualFileSystem* getVFS() { return vfs.get(); }

private:
    using construct_bm_func_t =
        std::function<std::unique_ptr<storage::BufferManager>(const Database&)>;

    struct QueryIDGenerator {
        uint64_t queryID = 0;
        std::mutex queryIDLock;
    };

    static std::unique_ptr<storage::BufferManager> initBufferManager(const Database& db);
    void initMembers(std::string_view dbPath, construct_bm_func_t initBmFunc);

    // factory method only to be used for tests
    Database(std::string_view databasePath, SystemConfig systemConfig,
        construct_bm_func_t constructBMFunc);

    void validatePathInReadOnly() const;

private:
    std::string databasePath;
    DBConfig dbConfig;
    std::unique_ptr<common::VirtualFileSystem> vfs;
    std::unique_ptr<storage::BufferManager> bufferManager;
    std::unique_ptr<storage::MemoryManager> memoryManager;
    std::unique_ptr<processor::QueryProcessor> queryProcessor;
    std::unique_ptr<catalog::Catalog> catalog;
    std::unique_ptr<storage::StorageManager> storageManager;
    std::unique_ptr<transaction::TransactionManager> transactionManager;
    std::unique_ptr<common::FileInfo> lockFile;
    std::unique_ptr<DatabaseManager> databaseManager;
    std::unique_ptr<extension::ExtensionManager> extensionManager;
    QueryIDGenerator queryIDGenerator;
    std::shared_ptr<common::DatabaseLifeCycleManager> dbLifeCycleManager;
    std::vector<std::unique_ptr<extension::TransformerExtension>> transformerExtensions;
    std::vector<std::unique_ptr<extension::BinderExtension>> binderExtensions;
    std::vector<std::unique_ptr<extension::PlannerExtension>> plannerExtensions;
    std::vector<std::unique_ptr<extension::MapperExtension>> mapperExtensions;
};

} // namespace main
} // namespace lbug
