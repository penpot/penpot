#include "main/client_context.h"

#include "binder/binder.h"
#include "common/exception/checkpoint.h"
#include "common/exception/connection.h"
#include "common/exception/runtime.h"
#include "common/file_system/virtual_file_system.h"
#include "common/random_engine.h"
#include "common/string_utils.h"
#include "common/task_system/progress_bar.h"
#include "extension/extension.h"
#include "extension/extension_manager.h"
#include "graph/graph_entry_set.h"
#include "main/attached_database.h"
#include "main/database.h"
#include "main/database_manager.h"
#include "main/db_config.h"
#include "optimizer/optimizer.h"
#include "parser/parser.h"
#include "parser/visitor/standalone_call_rewriter.h"
#include "parser/visitor/statement_read_write_analyzer.h"
#include "planner/planner.h"
#include "processor/plan_mapper.h"
#include "processor/processor.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/spiller.h"
#include "storage/storage_manager.h"
#include "transaction/transaction_context.h"
#include <processor/warning_context.h>

#if defined(_WIN32)
#include "common/windows_utils.h"
#endif

using namespace lbug::parser;
using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::planner;
using namespace lbug::processor;
using namespace lbug::transaction;

namespace lbug {
namespace main {

ActiveQuery::ActiveQuery() : interrupted{false} {}

void ActiveQuery::reset() {
    interrupted = false;
    timer = Timer();
}

ClientContext::ClientContext(Database* database) : localDatabase{database} {
    transactionContext = std::make_unique<TransactionContext>(*this);
    randomEngine = std::make_unique<RandomEngine>();
    remoteDatabase = nullptr;
    graphEntrySet = std::make_unique<graph::GraphEntrySet>();
    clientConfig.homeDirectory = getUserHomeDir();
    clientConfig.fileSearchPath = "";
    clientConfig.enableSemiMask = ClientConfigDefault::ENABLE_SEMI_MASK;
    clientConfig.enableZoneMap = ClientConfigDefault::ENABLE_ZONE_MAP;
    clientConfig.numThreads = database->dbConfig.maxNumThreads;
    clientConfig.timeoutInMS = ClientConfigDefault::TIMEOUT_IN_MS;
    clientConfig.varLengthMaxDepth = ClientConfigDefault::VAR_LENGTH_MAX_DEPTH;
    clientConfig.enableProgressBar = ClientConfigDefault::ENABLE_PROGRESS_BAR;
    clientConfig.showProgressAfter = ClientConfigDefault::SHOW_PROGRESS_AFTER;
    clientConfig.recursivePatternSemantic = ClientConfigDefault::RECURSIVE_PATTERN_SEMANTIC;
    clientConfig.recursivePatternCardinalityScaleFactor =
        ClientConfigDefault::RECURSIVE_PATTERN_FACTOR;
    clientConfig.disableMapKeyCheck = ClientConfigDefault::DISABLE_MAP_KEY_CHECK;
    clientConfig.warningLimit = ClientConfigDefault::WARNING_LIMIT;
    progressBar = std::make_unique<ProgressBar>(clientConfig.enableProgressBar);
    warningContext = std::make_unique<WarningContext>(&clientConfig);
}

ClientContext::~ClientContext() {
    if (preventTransactionRollbackOnDestruction) {
        return;
    }
    if (Transaction::Get(*this)) {
        getDatabase()->transactionManager->rollback(*this, Transaction::Get(*this));
    }
}

const DBConfig* ClientContext::getDBConfig() const {
    return &getDatabase()->dbConfig;
}

DBConfig* ClientContext::getDBConfigUnsafe() const {
    return &getDatabase()->dbConfig;
}

uint64_t ClientContext::getTimeoutRemainingInMS() const {
    KU_ASSERT(hasTimeout());
    const auto elapsed = activeQuery.timer.getElapsedTimeInMS();
    return elapsed >= clientConfig.timeoutInMS ? 0 : clientConfig.timeoutInMS - elapsed;
}

void ClientContext::startTimer() {
    if (hasTimeout()) {
        activeQuery.timer.start();
    }
}

void ClientContext::setQueryTimeOut(uint64_t timeoutInMS) {
    lock_t lck{mtx};
    clientConfig.timeoutInMS = timeoutInMS;
}

uint64_t ClientContext::getQueryTimeOut() const {
    return clientConfig.timeoutInMS;
}

void ClientContext::setMaxNumThreadForExec(uint64_t numThreads) {
    lock_t lck{mtx};
    if (numThreads == 0) {
        numThreads = localDatabase->dbConfig.maxNumThreads;
    }
    clientConfig.numThreads = numThreads;
}

uint64_t ClientContext::getMaxNumThreadForExec() const {
    return clientConfig.numThreads;
}

Value ClientContext::getCurrentSetting(const std::string& optionName) const {
    auto lowerCaseOptionName = optionName;
    StringUtils::toLower(lowerCaseOptionName);
    // Firstly, try to find in built-in options.
    const auto option = DBConfig::getOptionByName(lowerCaseOptionName);
    if (option != nullptr) {
        return option->getSetting(this);
    }
    // Secondly, try to find in current client session.
    if (extensionOptionValues.contains(lowerCaseOptionName)) {
        return extensionOptionValues.at(lowerCaseOptionName);
    }
    // Lastly, find the default value in db clientConfig.
    const auto defaultOption = getExtensionOption(lowerCaseOptionName);
    if (defaultOption != nullptr) {
        return defaultOption->defaultValue;
    }
    throw RuntimeException{"Invalid option name: " + lowerCaseOptionName + "."};
}

void ClientContext::addScanReplace(function::ScanReplacement scanReplacement) {
    scanReplacements.push_back(std::move(scanReplacement));
}

std::unique_ptr<function::ScanReplacementData> ClientContext::tryReplaceByName(
    const std::string& objectName) const {
    for (auto& scanReplacement : scanReplacements) {
        auto replaceHandles = scanReplacement.lookupFunc(objectName);
        if (replaceHandles.empty()) {
            continue; // Fail to replace.
        }
        return scanReplacement.replaceFunc(std::span(replaceHandles.begin(), replaceHandles.end()));
    }
    return {};
}

std::unique_ptr<function::ScanReplacementData> ClientContext::tryReplaceByHandle(
    function::scan_replace_handle_t handle) const {
    auto handleSpan = std::span{&handle, 1};
    for (auto& scanReplacement : scanReplacements) {
        auto replaceData = scanReplacement.replaceFunc(handleSpan);
        if (replaceData == nullptr) {
            continue; // Fail to replace.
        }
        return replaceData;
    }
    return nullptr;
}

void ClientContext::setExtensionOption(std::string name, Value value) {
    StringUtils::toLower(name);
    extensionOptionValues.insert_or_assign(name, std::move(value));
}

const main::ExtensionOption* ClientContext::getExtensionOption(std::string optionName) const {
    return localDatabase->extensionManager->getExtensionOption(optionName);
}

std::string ClientContext::getExtensionDir() const {
    return stringFormat("{}/.lbug/extension/{}/{}/", clientConfig.homeDirectory,
        LBUG_EXTENSION_VERSION, extension::getPlatform());
}

std::string ClientContext::getDatabasePath() const {
    return localDatabase->databasePath;
}

Database* ClientContext::getDatabase() const {
    return localDatabase;
}

AttachedLbugDatabase* ClientContext::getAttachedDatabase() const {
    return remoteDatabase;
}

bool ClientContext::isInMemory() const {
    if (remoteDatabase != nullptr) {
        // If we are connected to a remote database, we assume it is not in memory.
        return false;
    }
    return localDatabase->storageManager->isInMemory();
}

std::string ClientContext::getEnvVariable(const std::string& name) {
#if defined(_WIN32)
    auto envValue = WindowsUtils::utf8ToUnicode(name.c_str());
    auto result = _wgetenv(envValue.c_str());
    if (!result) {
        return std::string();
    }
    return WindowsUtils::unicodeToUTF8(result);
#else
    const char* env = getenv(name.c_str()); // NOLINT(*-mt-unsafe)
    if (!env) {
        return std::string();
    }
    return env;
#endif
}

std::string ClientContext::getUserHomeDir() {
#if defined(_WIN32)
    return getEnvVariable("USERPROFILE");
#else
    return getEnvVariable("HOME");
#endif
}

void ClientContext::setDefaultDatabase(AttachedLbugDatabase* defaultDatabase_) {
    remoteDatabase = defaultDatabase_;
}

bool ClientContext::hasDefaultDatabase() const {
    return remoteDatabase != nullptr;
}

void ClientContext::addScalarFunction(std::string name, function::function_set definitions) {
    TransactionHelper::runFuncInTransaction(
        *transactionContext,
        [&]() {
            localDatabase->catalog->addFunction(Transaction::Get(*this),
                CatalogEntryType::SCALAR_FUNCTION_ENTRY, std::move(name), std::move(definitions));
        },
        false /*readOnlyStatement*/, false /*isTransactionStatement*/,
        TransactionHelper::TransactionCommitAction::COMMIT_IF_NEW);
}

void ClientContext::removeScalarFunction(const std::string& name) {
    TransactionHelper::runFuncInTransaction(
        *transactionContext,
        [&]() { localDatabase->catalog->dropFunction(Transaction::Get(*this), name); },
        false /*readOnlyStatement*/, false /*isTransactionStatement*/,
        TransactionHelper::TransactionCommitAction::COMMIT_IF_NEW);
}

void ClientContext::cleanUp() {
    VirtualFileSystem::GetUnsafe(*this)->cleanUP(this);
}

std::unique_ptr<PreparedStatement> ClientContext::prepareWithParams(std::string_view query,
    std::unordered_map<std::string, std::unique_ptr<Value>> inputParams) {
    std::unique_lock lck{mtx};
    auto parsedStatements = std::vector<std::shared_ptr<Statement>>();
    try {
        parsedStatements = parseQuery(query);
    } catch (std::exception& exception) {
        return PreparedStatement::getPreparedStatementWithError(exception.what());
    }
    if (parsedStatements.size() > 1) {
        return PreparedStatement::getPreparedStatementWithError(
            "Connection Exception: We do not support prepare multiple statements.");
    }

    // The binder deals with the parameter values as shared ptrs
    // Copy the params to a new map that matches the format that the binder expects
    std::unordered_map<std::string, std::shared_ptr<Value>> inputParamsTmp;
    for (auto& [key, value] : inputParams) {
        inputParamsTmp.insert(std::make_pair(key, std::make_shared<Value>(*value)));
    }
    auto [preparedStatement, cachedStatement] = prepareNoLock(parsedStatements[0],
        true /*shouldCommitNewTransaction*/, std::move(inputParamsTmp));
    preparedStatement->cachedPreparedStatementName =
        cachedPreparedStatementManager.addStatement(std::move(cachedStatement));
    useInternalCatalogEntry_ = false;
    return std::move(preparedStatement);
}

static void bindParametersNoLock(PreparedStatement& preparedStatement,
    const std::unordered_map<std::string, std::unique_ptr<Value>>& inputParams) {
    for (auto& key : preparedStatement.getKnownParameters()) {
        if (inputParams.contains(key)) {
            // Found input. Update parameter map.
            preparedStatement.updateParameter(key, inputParams.at(key).get());
        }
    }
    for (auto& key : preparedStatement.getUnknownParameters()) {
        if (!inputParams.contains(key)) {
            throw Exception("Parameter " + key + " not found.");
        }
        preparedStatement.addParameter(key, inputParams.at(key).get());
    }
}

std::unique_ptr<QueryResult> ClientContext::executeWithParams(PreparedStatement* preparedStatement,
    std::unordered_map<std::string, std::unique_ptr<Value>> inputParams,
    std::optional<uint64_t> queryID) { // NOLINT(performance-unnecessary-value-param): It doesn't
    // make sense to pass the map as a const reference.
    lock_t lck{mtx};
    if (!preparedStatement->isSuccess()) {
        return QueryResult::getQueryResultWithError(preparedStatement->errMsg);
    }
    try {
        bindParametersNoLock(*preparedStatement, inputParams);
    } catch (std::exception& e) {
        return QueryResult::getQueryResultWithError(e.what());
    }
    auto name = preparedStatement->getName();
    // LCOV_EXCL_START
    // The following should never happen. But we still throw just in case.
    if (!cachedPreparedStatementManager.containsStatement(name)) {
        return QueryResult::getQueryResultWithError(
            stringFormat("Cannot find prepared statement with name {}.", name));
    }
    // LCOV_EXCL_STOP
    auto cachedStatement = cachedPreparedStatementManager.getCachedStatement(name);
    // rebind
    auto [newPreparedStatement, newCachedStatement] =
        prepareNoLock(cachedStatement->parsedStatement, false /*shouldCommitNewTransaction*/,
            preparedStatement->parameterMap);
    useInternalCatalogEntry_ = false;
    return executeNoLock(newPreparedStatement.get(), newCachedStatement.get(), queryID);
}

std::unique_ptr<QueryResult> ClientContext::query(std::string_view query,
    std::optional<uint64_t> queryID, QueryConfig config) {
    lock_t lck{mtx};
    return queryNoLock(query, queryID, config);
}

std::unique_ptr<QueryResult> ClientContext::queryNoLock(std::string_view query,
    std::optional<uint64_t> queryID, QueryConfig config) {
    auto parsedStatements = std::vector<std::shared_ptr<Statement>>();
    try {
        parsedStatements = parseQuery(query);
    } catch (std::exception& exception) {
        return QueryResult::getQueryResultWithError(exception.what());
    }
    std::unique_ptr<QueryResult> queryResult;
    QueryResult* lastResult = nullptr;
    double internalCompilingTime = 0.0, internalExecutionTime = 0.0;
    for (const auto& statement : parsedStatements) {
        auto [preparedStatement, cachedStatement] =
            prepareNoLock(statement, false /*shouldCommitNewTransaction*/);
        auto currentQueryResult =
            executeNoLock(preparedStatement.get(), cachedStatement.get(), queryID, config);
        if (!currentQueryResult->isSuccess()) {
            if (!lastResult) {
                queryResult = std::move(currentQueryResult);
            } else {
                queryResult->addNextResult(std::move(currentQueryResult));
            }
            break;
        }
        auto currentQuerySummary = currentQueryResult->getQuerySummary();
        if (statement->isInternal()) {
            // The result of internal statements should be invisible to end users. Skip chaining the
            // result of internal statements to the final result to end users.
            internalCompilingTime += currentQuerySummary->getCompilingTime();
            internalExecutionTime += currentQuerySummary->getExecutionTime();
            continue;
        }
        currentQuerySummary->incrementCompilingTime(internalCompilingTime);
        currentQuerySummary->incrementExecutionTime(internalExecutionTime);
        if (!lastResult) {
            // first result of the query
            queryResult = std::move(currentQueryResult);
            lastResult = queryResult.get();
        } else {
            auto current = currentQueryResult.get();
            lastResult->addNextResult(std::move(currentQueryResult));
            lastResult = current;
        }
    }
    useInternalCatalogEntry_ = false;
    return queryResult;
}

std::vector<std::shared_ptr<Statement>> ClientContext::parseQuery(std::string_view query) {
    if (query.empty()) {
        throw ConnectionException("Query is empty.");
    }
    std::vector<std::shared_ptr<Statement>> statements;
    auto parserTimer = TimeMetric(true /*enable*/);
    parserTimer.start();
    auto parsedStatements = Parser::parseQuery(query, localDatabase->getTransformerExtensions());
    parserTimer.stop();
    const auto avgParsingTime = parserTimer.getElapsedTimeMS() / parsedStatements.size() / 1.0;
    StandaloneCallRewriter standaloneCallAnalyzer{this, parsedStatements.size() == 1};
    for (auto i = 0u; i < parsedStatements.size(); i++) {
        auto rewriteQuery = standaloneCallAnalyzer.getRewriteQuery(*parsedStatements[i]);
        if (rewriteQuery.empty()) {
            parsedStatements[i]->setParsingTime(avgParsingTime);
            statements.push_back(std::move(parsedStatements[i]));
        } else {
            parserTimer.start();
            auto rewrittenStatements =
                Parser::parseQuery(rewriteQuery, localDatabase->getTransformerExtensions());
            parserTimer.stop();
            const auto avgRewriteParsingTime =
                parserTimer.getElapsedTimeMS() / rewrittenStatements.size() / 1.0;
            KU_ASSERT(rewrittenStatements.size() >= 1);
            for (auto j = 0u; j < rewrittenStatements.size() - 1; j++) {
                rewrittenStatements[j]->setParsingTime(avgParsingTime + avgRewriteParsingTime);
                rewrittenStatements[j]->setToInternal();
                statements.push_back(std::move(rewrittenStatements[j]));
            }
            auto lastRewrittenStatement = rewrittenStatements.back();
            lastRewrittenStatement->setParsingTime(avgParsingTime + avgRewriteParsingTime);
            statements.push_back(std::move(lastRewrittenStatement));
        }
    }
    return statements;
}

void ClientContext::validateTransaction(bool readOnly, bool requireTransaction) const {
    if (!canExecuteWriteQuery() && !readOnly) {
        throw ConnectionException("Cannot execute write operations in a read-only database!");
    }
    if (requireTransaction && transactionContext->hasActiveTransaction()) {
        KU_ASSERT(!transactionContext->isAutoTransaction());
        transactionContext->validateManualTransaction(readOnly);
    }
}

ClientContext::PrepareResult ClientContext::prepareNoLock(
    std::shared_ptr<Statement> parsedStatement, bool shouldCommitNewTransaction,
    std::unordered_map<std::string, std::shared_ptr<Value>> inputParams) {
    auto preparedStatement = std::make_unique<PreparedStatement>();
    auto cachedStatement = std::make_unique<CachedPreparedStatement>();
    cachedStatement->parsedStatement = parsedStatement;
    cachedStatement->useInternalCatalogEntry = useInternalCatalogEntry_;
    auto prepareTimer = TimeMetric(true /* enable */);
    prepareTimer.start();
    try {
        preparedStatement->preparedSummary.statementType = parsedStatement->getStatementType();
        auto readWriteAnalyzer = StatementReadWriteAnalyzer(this);
        TransactionHelper::runFuncInTransaction(
            *transactionContext, [&]() -> void { readWriteAnalyzer.visit(*parsedStatement); },
            true /* readOnly */, false /* */,
            TransactionHelper::TransactionCommitAction::COMMIT_IF_NEW);
        preparedStatement->readOnly = readWriteAnalyzer.isReadOnly();
        validateTransaction(preparedStatement->readOnly, parsedStatement->requireTransaction());
        TransactionHelper::runFuncInTransaction(
            *transactionContext,
            [&]() -> void {
                auto binder = Binder(this, localDatabase->getBinderExtensions());
                auto expressionBinder = binder.getExpressionBinder();
                for (auto& [name, value] : inputParams) {
                    expressionBinder->addParameter(name, value);
                }
                const auto boundStatement = binder.bind(*parsedStatement);
                preparedStatement->unknownParameters = expressionBinder->getUnknownParameters();
                preparedStatement->parameterMap = expressionBinder->getKnownParameters();
                cachedStatement->columns = boundStatement->getStatementResult()->getColumns();
                auto planner = Planner(this);
                auto bestPlan = planner.planStatement(*boundStatement);
                optimizer::Optimizer::optimize(&bestPlan, this, planner.getCardinalityEstimator());
                cachedStatement->logicalPlan = std::make_unique<LogicalPlan>(std::move(bestPlan));
            },
            preparedStatement->isReadOnly(),
            preparedStatement->getStatementType() == StatementType::TRANSACTION,
            TransactionHelper::getAction(shouldCommitNewTransaction,
                false /*shouldCommitAutoTransaction*/));
    } catch (std::exception& exception) {
        preparedStatement->success = false;
        preparedStatement->errMsg = exception.what();
    }
    prepareTimer.stop();
    preparedStatement->preparedSummary.compilingTime =
        parsedStatement->getParsingTime() + prepareTimer.getElapsedTimeMS();
    return {std::move(preparedStatement), std::move(cachedStatement)};
}

std::unique_ptr<QueryResult> ClientContext::executeNoLock(PreparedStatement* preparedStatement,
    CachedPreparedStatement* cachedStatement, std::optional<uint64_t> queryID,
    QueryConfig queryConfig) {
    if (!preparedStatement->isSuccess()) {
        return QueryResult::getQueryResultWithError(preparedStatement->errMsg);
    }
    useInternalCatalogEntry_ = cachedStatement->useInternalCatalogEntry;
    this->resetActiveQuery();
    this->startTimer();
    auto executingTimer = TimeMetric(true /* enable */);
    executingTimer.start();
    std::unique_ptr<QueryResult> result;
    try {
        bool isTransactionStatement =
            preparedStatement->getStatementType() == StatementType::TRANSACTION;
        TransactionHelper::runFuncInTransaction(
            *transactionContext,
            [&]() -> void {
                const auto profiler = std::make_unique<Profiler>();
                profiler->enabled = cachedStatement->logicalPlan->isProfile();
                if (!queryID) {
                    queryID = localDatabase->getNextQueryID();
                }
                const auto executionContext =
                    std::make_unique<ExecutionContext>(profiler.get(), this, *queryID);
                auto mapper = PlanMapper(executionContext.get());
                const auto physicalPlan = mapper.getPhysicalPlan(cachedStatement->logicalPlan.get(),
                    cachedStatement->columns, queryConfig.resultType, queryConfig.arrowConfig);
                if (isTransactionStatement) {
                    result = localDatabase->queryProcessor->execute(physicalPlan.get(),
                        executionContext.get());
                } else {
                    if (preparedStatement->getStatementType() == StatementType::COPY_FROM) {
                        // Note: We always force checkpoint for COPY_FROM statement.
                        Transaction::Get(*this)->setForceCheckpoint();
                    }
                    result = localDatabase->queryProcessor->execute(physicalPlan.get(),
                        executionContext.get());
                }
            },
            preparedStatement->isReadOnly(), isTransactionStatement,
            TransactionHelper::getAction(true /*shouldCommitNewTransaction*/,
                !isTransactionStatement /*shouldCommitAutoTransaction*/));
    } catch (std::exception& e) {
        useInternalCatalogEntry_ = false;
        return handleFailedExecution(queryID, e);
    }
    const auto memoryManager = storage::MemoryManager::Get(*this);
    memoryManager->getBufferManager()->getSpillerOrSkip([](auto& spiller) { spiller.clearFile(); });
    executingTimer.stop();
    result->setColumnNames(cachedStatement->getColumnNames());
    result->setColumnTypes(cachedStatement->getColumnTypes());
    auto summary = std::make_unique<QuerySummary>(preparedStatement->preparedSummary);
    summary->setExecutionTime(executingTimer.getElapsedTimeMS());
    result->setQuerySummary(std::move(summary));
    return result;
}

std::unique_ptr<QueryResult> ClientContext::handleFailedExecution(std::optional<uint64_t> queryID,
    const std::exception& e) const {
    const auto memoryManager = storage::MemoryManager::Get(*this);
    memoryManager->getBufferManager()->getSpillerOrSkip([](auto& spiller) { spiller.clearFile(); });
    if (queryID.has_value()) {
        progressBar->endProgress(queryID.value());
    }
    return QueryResult::getQueryResultWithError(e.what());
}

ClientContext::TransactionHelper::TransactionCommitAction
ClientContext::TransactionHelper::getAction(bool commitIfNew, bool commitIfAuto) {
    if (commitIfNew && commitIfAuto) {
        return TransactionCommitAction::COMMIT_NEW_OR_AUTO;
    }
    if (commitIfNew) {
        return TransactionCommitAction::COMMIT_IF_NEW;
    }
    if (commitIfAuto) {
        return TransactionCommitAction::COMMIT_IF_AUTO;
    }
    return TransactionCommitAction::NOT_COMMIT;
}

// If there is an active transaction in the context, we execute the function in the current active
// transaction. If there is no active transaction, we start an auto commit transaction.
void ClientContext::TransactionHelper::runFuncInTransaction(TransactionContext& context,
    const std::function<void()>& fun, bool readOnlyStatement, bool isTransactionStatement,
    TransactionCommitAction action) {
    KU_ASSERT(context.isAutoTransaction() || context.hasActiveTransaction());
    const bool requireNewTransaction =
        context.isAutoTransaction() && !context.hasActiveTransaction() && !isTransactionStatement;
    if (requireNewTransaction) {
        context.beginAutoTransaction(readOnlyStatement);
    }
    try {
        fun();
        if ((requireNewTransaction && commitIfNew(action)) ||
            (context.isAutoTransaction() && commitIfAuto(action))) {
            context.commit();
        }
    } catch (CheckpointException&) {
        context.clearTransaction();
        throw;
    } catch (std::exception&) {
        context.rollback();
        throw;
    }
}

bool ClientContext::canExecuteWriteQuery() const {
    if (getDBConfig()->readOnly) {
        return false;
    }
    // Note: we can only attach a remote lbug database in read-only mode and only one
    // remote lbug database can be attached.
    const auto dbManager = DatabaseManager::Get(*this);
    for (const auto& attachedDB : dbManager->getAttachedDatabases()) {
        if (attachedDB->getDBType() == ATTACHED_LBUG_DB_TYPE) {
            return false;
        }
    }
    return true;
}

} // namespace main
} // namespace lbug
