#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <optional>

#include "common/arrow/arrow_result_config.h"
#include "common/timer.h"
#include "common/types/value/value.h"
#include "function/table/scan_replacement.h"
#include "main/client_config.h"
#include "main/prepared_statement_manager.h"
#include "main/query_result.h"
#include "prepared_statement.h"

namespace lbug {
namespace common {
class RandomEngine;
class TaskScheduler;
class ProgressBar;
class VirtualFileSystem;
} // namespace common

namespace catalog {
class Catalog;
}

namespace extension {
class ExtensionManager;
} // namespace extension

namespace graph {
class GraphEntrySet;
}

namespace storage {
class StorageManager;
}

namespace processor {
class ImportDB;
class WarningContext;
} // namespace processor

namespace transaction {
class TransactionContext;
class Transaction;
} // namespace transaction

namespace main {
struct DBConfig;
class Database;
class DatabaseManager;
class AttachedLbugDatabase;
struct SpillToDiskSetting;
struct ExtensionOption;
class EmbeddedShell;

struct ActiveQuery {
    explicit ActiveQuery();
    std::atomic<bool> interrupted;
    common::Timer timer;

    void reset();
};

/**
 * @brief Contain client side configuration. We make profiler associated per query, so the profiler
 * is not maintained in the client context.
 */
class LBUG_API ClientContext {
    friend class Connection;
    friend class EmbeddedShell;
    friend struct SpillToDiskSetting;
    friend class processor::ImportDB;
    friend class processor::WarningContext;
    friend class transaction::TransactionContext;
    friend class common::RandomEngine;
    friend class common::ProgressBar;
    friend class graph::GraphEntrySet;

public:
    explicit ClientContext(Database* database);
    ~ClientContext();

    // Client config
    const ClientConfig* getClientConfig() const { return &clientConfig; }
    ClientConfig* getClientConfigUnsafe() { return &clientConfig; }

    // Database config
    const DBConfig* getDBConfig() const;
    DBConfig* getDBConfigUnsafe() const;
    common::Value getCurrentSetting(const std::string& optionName) const;

    // Timer and timeout
    void interrupt() { activeQuery.interrupted = true; }
    bool interrupted() const { return activeQuery.interrupted; }
    bool hasTimeout() const { return clientConfig.timeoutInMS != 0; }
    void setQueryTimeOut(uint64_t timeoutInMS);
    uint64_t getQueryTimeOut() const;
    void startTimer();
    uint64_t getTimeoutRemainingInMS() const;
    void resetActiveQuery() { activeQuery.reset(); }

    // Parallelism
    void setMaxNumThreadForExec(uint64_t numThreads);
    uint64_t getMaxNumThreadForExec() const;

    // Replace function.
    void addScanReplace(function::ScanReplacement scanReplacement);
    std::unique_ptr<function::ScanReplacementData> tryReplaceByName(
        const std::string& objectName) const;
    std::unique_ptr<function::ScanReplacementData> tryReplaceByHandle(
        function::scan_replace_handle_t handle) const;

    // Extension
    void setExtensionOption(std::string name, common::Value value);
    const ExtensionOption* getExtensionOption(std::string optionName) const;
    std::string getExtensionDir() const;

    // Getters.
    std::string getDatabasePath() const;
    Database* getDatabase() const;
    AttachedLbugDatabase* getAttachedDatabase() const;

    const CachedPreparedStatementManager& getCachedPreparedStatementManager() const {
        return cachedPreparedStatementManager;
    }

    bool isInMemory() const;

    static std::string getEnvVariable(const std::string& name);
    static std::string getUserHomeDir();

    void setDefaultDatabase(AttachedLbugDatabase* defaultDatabase_);
    bool hasDefaultDatabase() const;
    void setUseInternalCatalogEntry(bool useInternalCatalogEntry) {
        this->useInternalCatalogEntry_ = useInternalCatalogEntry;
    }
    bool useInternalCatalogEntry() const {
        return clientConfig.enableInternalCatalog ? true : useInternalCatalogEntry_;
    }

    void addScalarFunction(std::string name, function::function_set definitions);
    void removeScalarFunction(const std::string& name);

    void cleanUp();

    struct QueryConfig {
        QueryResultType resultType;
        common::ArrowResultConfig arrowConfig;

        QueryConfig() : resultType{QueryResultType::FTABLE}, arrowConfig{} {}
        QueryConfig(QueryResultType resultType, common::ArrowResultConfig arrowConfig)
            : resultType{resultType}, arrowConfig{arrowConfig} {}
    };

    std::unique_ptr<QueryResult> query(std::string_view queryStatement,
        std::optional<uint64_t> queryID = std::nullopt, QueryConfig config = {});
    std::unique_ptr<PreparedStatement> prepareWithParams(std::string_view query,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> inputParams = {});
    std::unique_ptr<QueryResult> executeWithParams(PreparedStatement* preparedStatement,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> inputParams,
        std::optional<uint64_t> queryID = std::nullopt);

    struct TransactionHelper {
        enum class TransactionCommitAction : uint8_t {
            COMMIT_IF_NEW,
            COMMIT_IF_AUTO,
            COMMIT_NEW_OR_AUTO,
            NOT_COMMIT
        };
        static bool commitIfNew(TransactionCommitAction action) {
            return action == TransactionCommitAction::COMMIT_IF_NEW ||
                   action == TransactionCommitAction::COMMIT_NEW_OR_AUTO;
        }
        static bool commitIfAuto(TransactionCommitAction action) {
            return action == TransactionCommitAction::COMMIT_IF_AUTO ||
                   action == TransactionCommitAction::COMMIT_NEW_OR_AUTO;
        }
        static TransactionCommitAction getAction(bool commitIfNew, bool commitIfAuto);
        static void runFuncInTransaction(transaction::TransactionContext& context,
            const std::function<void()>& fun, bool readOnlyStatement, bool isTransactionStatement,
            TransactionCommitAction action);
    };

private:
    void validateTransaction(bool readOnly, bool requireTransaction) const;

    std::vector<std::shared_ptr<parser::Statement>> parseQuery(std::string_view query);

    struct PrepareResult {
        std::unique_ptr<PreparedStatement> preparedStatement;
        std::unique_ptr<CachedPreparedStatement> cachedPreparedStatement;
    };

    PrepareResult prepareNoLock(std::shared_ptr<parser::Statement> parsedStatement,
        bool shouldCommitNewTransaction,
        std::unordered_map<std::string, std::shared_ptr<common::Value>> inputParams = {});

    template<typename T, typename... Args>
    std::unique_ptr<QueryResult> executeWithParams(PreparedStatement* preparedStatement,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> params,
        std::pair<std::string, T> arg, std::pair<std::string, Args>... args) {
        auto name = arg.first;
        auto val = std::make_unique<common::Value>((T)arg.second);
        params.insert({name, std::move(val)});
        return executeWithParams(preparedStatement, std::move(params), args...);
    }

    std::unique_ptr<QueryResult> executeNoLock(PreparedStatement* preparedStatement,
        CachedPreparedStatement* cachedPreparedStatement,
        std::optional<uint64_t> queryID = std::nullopt, QueryConfig config = {});
    std::unique_ptr<QueryResult> queryNoLock(std::string_view query,
        std::optional<uint64_t> queryID = std::nullopt, QueryConfig config = {});

    bool canExecuteWriteQuery() const;

    std::unique_ptr<QueryResult> handleFailedExecution(std::optional<uint64_t> queryID,
        const std::exception& e) const;

    std::mutex mtx;
    // Client side configurable settings.
    ClientConfig clientConfig;
    // Current query.
    ActiveQuery activeQuery;
    // Cache prepare statement.
    CachedPreparedStatementManager cachedPreparedStatementManager;
    // Transaction context.
    std::unique_ptr<transaction::TransactionContext> transactionContext;
    // Replace external object as pointer Value;
    std::vector<function::ScanReplacement> scanReplacements;
    // Extension configurable settings.
    std::unordered_map<std::string, common::Value> extensionOptionValues;
    // Random generator for UUID.
    std::unique_ptr<common::RandomEngine> randomEngine;
    // Local database.
    Database* localDatabase;
    // Remote database.
    AttachedLbugDatabase* remoteDatabase;
    // Progress bar.
    std::unique_ptr<common::ProgressBar> progressBar;
    // Warning information
    std::unique_ptr<processor::WarningContext> warningContext;
    // Graph entries
    std::unique_ptr<graph::GraphEntrySet> graphEntrySet;
    // Whether the query can access internal tables/sequences or not.
    bool useInternalCatalogEntry_ = false;
    // Whether the transaction should be rolled back on destruction. If the parent database is
    // closed, the rollback should be prevented or it will SEGFAULT.
    bool preventTransactionRollbackOnDestruction = false;
};

} // namespace main
} // namespace lbug
