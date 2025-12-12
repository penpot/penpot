#include "main/database.h"

#include "extension/binder_extension.h"
#include "extension/extension_manager.h"
#include "extension/mapper_extension.h"
#include "extension/planner_extension.h"
#include "extension/transformer_extension.h"
#include "main/client_context.h"
#include "main/database_manager.h"
#include "storage/buffer_manager/buffer_manager.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <unistd.h>
#endif

#include "common/exception/exception.h"
#include "common/file_system/virtual_file_system.h"
#include "main/db_config.h"
#include "processor/processor.h"
#include "storage/storage_extension.h"
#include "storage/storage_manager.h"
#include "storage/storage_utils.h"
#include "transaction/transaction_manager.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::transaction;

namespace lbug {
namespace main {

SystemConfig::SystemConfig(uint64_t bufferPoolSize_, uint64_t maxNumThreads, bool enableCompression,
    bool readOnly, uint64_t maxDBSize, bool autoCheckpoint, uint64_t checkpointThreshold,
    bool forceCheckpointOnClose, bool throwOnWalReplayFailure, bool enableChecksums
#if defined(__APPLE__)
    ,
    uint32_t threadQos
#endif
    )
    : maxNumThreads{maxNumThreads}, enableCompression{enableCompression}, readOnly{readOnly},
      autoCheckpoint{autoCheckpoint}, checkpointThreshold{checkpointThreshold},
      forceCheckpointOnClose{forceCheckpointOnClose},
      throwOnWalReplayFailure(throwOnWalReplayFailure), enableChecksums(enableChecksums) {
#if defined(__APPLE__)
    this->threadQos = threadQos;
#endif
    if (bufferPoolSize_ == -1u || bufferPoolSize_ == 0) {
#if defined(_WIN32)
        MEMORYSTATUSEX status;
        status.dwLength = sizeof(status);
        GlobalMemoryStatusEx(&status);
        auto systemMemSize = (std::uint64_t)status.ullTotalPhys;
#else
        auto systemMemSize = static_cast<std::uint64_t>(sysconf(_SC_PHYS_PAGES)) *
                             static_cast<std::uint64_t>(sysconf(_SC_PAGESIZE));
#endif
        bufferPoolSize_ = static_cast<uint64_t>(
            BufferPoolConstants::DEFAULT_PHY_MEM_SIZE_RATIO_FOR_BM *
            static_cast<double>(std::min(systemMemSize, static_cast<uint64_t>(UINTPTR_MAX))));
        // On 32-bit systems or systems with extremely large memory, the buffer pool size may
        // exceed the maximum size of a VMRegion. In this case, we set the buffer pool size to
        // 80% of the maximum size of a VMRegion.
        bufferPoolSize_ = static_cast<uint64_t>(std::min(static_cast<double>(bufferPoolSize_),
            BufferPoolConstants::DEFAULT_VM_REGION_MAX_SIZE *
                BufferPoolConstants::DEFAULT_PHY_MEM_SIZE_RATIO_FOR_BM));
    }
    bufferPoolSize = bufferPoolSize_;
#ifndef __SINGLE_THREADED__
    if (maxNumThreads == 0) {
        this->maxNumThreads = std::thread::hardware_concurrency();
    }
#else
    // In single-threaded mode, even if the user specifies a number of threads,
    // it will be ignored and set to 0.
    this->maxNumThreads = 1;
#endif
    if (maxDBSize == -1u) {
        maxDBSize = BufferPoolConstants::DEFAULT_VM_REGION_MAX_SIZE;
    }
    this->maxDBSize = maxDBSize;
}

Database::Database(std::string_view databasePath, SystemConfig systemConfig)
    : Database(databasePath, systemConfig, initBufferManager) {}

Database::Database(std::string_view databasePath, SystemConfig systemConfig,
    construct_bm_func_t constructBMFunc)
    : dbConfig(systemConfig) {
    initMembers(databasePath, constructBMFunc);
}

std::unique_ptr<BufferManager> Database::initBufferManager(const Database& db) {
    return std::make_unique<BufferManager>(db.databasePath,
        StorageUtils::getTmpFilePath(db.databasePath), db.dbConfig.bufferPoolSize,
        db.dbConfig.maxDBSize, db.vfs.get(), db.dbConfig.readOnly);
}

void Database::initMembers(std::string_view dbPath, construct_bm_func_t initBmFunc) {
    // To expand a path with home directory(~), we have to pass in a dummy clientContext which
    // handles the home directory expansion.
    const auto dbPathStr = std::string(dbPath);
    auto clientContext = ClientContext(this);
    databasePath = StorageUtils::expandPath(&clientContext, dbPathStr);

    if (std::filesystem::is_directory(databasePath)) {
        throw RuntimeException("Database path cannot be a directory: " + databasePath);
    }
    vfs = std::make_unique<VirtualFileSystem>(databasePath);
    validatePathInReadOnly();

    bufferManager = initBmFunc(*this);
    memoryManager = std::make_unique<MemoryManager>(bufferManager.get(), vfs.get());
#if defined(__APPLE__)
    queryProcessor =
        std::make_unique<processor::QueryProcessor>(dbConfig.maxNumThreads, dbConfig.threadQos);
#else
    queryProcessor = std::make_unique<processor::QueryProcessor>(dbConfig.maxNumThreads);
#endif

    catalog = std::make_unique<Catalog>();
    storageManager = std::make_unique<StorageManager>(databasePath, dbConfig.readOnly,
        dbConfig.enableChecksums, *memoryManager, dbConfig.enableCompression, vfs.get());
    transactionManager = std::make_unique<TransactionManager>(storageManager->getWAL());
    databaseManager = std::make_unique<DatabaseManager>();

    extensionManager = std::make_unique<extension::ExtensionManager>();
    dbLifeCycleManager = std::make_shared<DatabaseLifeCycleManager>();
    if (clientContext.isInMemory()) {
        storageManager->initDataFileHandle(vfs.get(), &clientContext);
        extensionManager->autoLoadLinkedExtensions(&clientContext);
        return;
    }
    StorageManager::recover(clientContext, dbConfig.throwOnWalReplayFailure,
        dbConfig.enableChecksums);
}

Database::~Database() {
    if (!dbConfig.readOnly && dbConfig.forceCheckpointOnClose) {
        try {
            ClientContext clientContext(this);
            transactionManager->checkpoint(clientContext);
        } catch (...) {} // NOLINT
    }
    dbLifeCycleManager->isDatabaseClosed = true;
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const function.
void Database::registerFileSystem(std::unique_ptr<FileSystem> fs) {
    vfs->registerFileSystem(std::move(fs));
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const function.
void Database::registerStorageExtension(std::string name,
    std::unique_ptr<StorageExtension> storageExtension) {
    extensionManager->registerStorageExtension(std::move(name), std::move(storageExtension));
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const function.
void Database::addExtensionOption(std::string name, LogicalTypeID type, Value defaultValue,
    bool isConfidential) {
    extensionManager->addExtensionOption(std::move(name), type, std::move(defaultValue),
        isConfidential);
}

void Database::addTransformerExtension(
    std::unique_ptr<extension::TransformerExtension> transformerExtension) {
    transformerExtensions.push_back(std::move(transformerExtension));
}

std::vector<extension::TransformerExtension*> Database::getTransformerExtensions() {
    std::vector<extension::TransformerExtension*> transformers;
    for (auto& transformerExtension : transformerExtensions) {
        transformers.push_back(transformerExtension.get());
    }
    return transformers;
}

void Database::addBinderExtension(
    std::unique_ptr<extension::BinderExtension> transformerExtension) {
    binderExtensions.push_back(std::move(transformerExtension));
}

std::vector<extension::BinderExtension*> Database::getBinderExtensions() {
    std::vector<extension::BinderExtension*> binders;
    for (auto& binderExtension : binderExtensions) {
        binders.push_back(binderExtension.get());
    }
    return binders;
}

void Database::addPlannerExtension(std::unique_ptr<extension::PlannerExtension> plannerExtension) {
    plannerExtensions.push_back(std::move(plannerExtension));
}

std::vector<extension::PlannerExtension*> Database::getPlannerExtensions() {
    std::vector<extension::PlannerExtension*> planners;
    for (auto& plannerExtension : plannerExtensions) {
        planners.push_back(plannerExtension.get());
    }
    return planners;
}

void Database::addMapperExtension(std::unique_ptr<extension::MapperExtension> mapperExtension) {
    mapperExtensions.push_back(std::move(mapperExtension));
}

std::vector<extension::MapperExtension*> Database::getMapperExtensions() {
    std::vector<extension::MapperExtension*> mappers;
    for (auto& mapperExtension : mapperExtensions) {
        mappers.push_back(mapperExtension.get());
    }
    return mappers;
}

std::vector<StorageExtension*> Database::getStorageExtensions() {
    return extensionManager->getStorageExtensions();
}

void Database::validatePathInReadOnly() const {
    if (dbConfig.readOnly) {
        if (DBConfig::isDBPathInMemory(databasePath)) {
            throw Exception("Cannot open an in-memory database under READ ONLY mode.");
        }
        if (!vfs->fileOrPathExists(databasePath)) {
            throw Exception("Cannot create an empty database under READ ONLY mode.");
        }
    }
}

uint64_t Database::getNextQueryID() {
    std::unique_lock lock(queryIDGenerator.queryIDLock);
    return queryIDGenerator.queryID++;
}

} // namespace main
} // namespace lbug
