#include "main/query_result.h"

#include "common/arrow/arrow_converter.h"
#include "main/query_result/materialized_query_result.h"
#include "processor/result/flat_tuple.h"

using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace main {

QueryResult::QueryResult()
    : type{QueryResultType::FTABLE}, nextQueryResult{nullptr}, queryResultIterator{this},
      dbLifeCycleManager{nullptr} {}

QueryResult::QueryResult(QueryResultType type)
    : type{type}, nextQueryResult{nullptr}, queryResultIterator{this}, dbLifeCycleManager{nullptr} {

}

QueryResult::QueryResult(QueryResultType type, std::vector<std::string> columnNames,
    std::vector<LogicalType> columnTypes)
    : type{type}, columnNames{std::move(columnNames)}, columnTypes{std::move(columnTypes)},
      nextQueryResult{nullptr}, queryResultIterator{this}, dbLifeCycleManager{nullptr} {
    tuple = std::make_shared<FlatTuple>(this->columnTypes);
}

QueryResult::~QueryResult() = default;

bool QueryResult::isSuccess() const {
    return success;
}

std::string QueryResult::getErrorMessage() const {
    return errMsg;
}

size_t QueryResult::getNumColumns() const {
    return columnTypes.size();
}

std::vector<std::string> QueryResult::getColumnNames() const {
    return columnNames;
}

std::vector<LogicalType> QueryResult::getColumnDataTypes() const {
    return LogicalType::copy(columnTypes);
}

QuerySummary* QueryResult::getQuerySummary() const {
    return querySummary.get();
}

QuerySummary* QueryResult::getQuerySummaryUnsafe() {
    return querySummary.get();
}

void QueryResult::checkDatabaseClosedOrThrow() const {
    if (!dbLifeCycleManager) {
        return;
    }
    dbLifeCycleManager->checkDatabaseClosedOrThrow();
}

bool QueryResult::hasNextQueryResult() const {
    checkDatabaseClosedOrThrow();
    return queryResultIterator.hasNextQueryResult();
}

QueryResult* QueryResult::getNextQueryResult() {
    checkDatabaseClosedOrThrow();
    if (hasNextQueryResult()) {
        ++queryResultIterator;
        return queryResultIterator.getCurrentResult();
    }
    return nullptr;
}

std::unique_ptr<ArrowSchema> QueryResult::getArrowSchema() const {
    checkDatabaseClosedOrThrow();
    return ArrowConverter::toArrowSchema(getColumnDataTypes(), getColumnNames(),
        false /* fallbackExtensionTypes */);
}

void QueryResult::validateQuerySucceed() const {
    if (!success) {
        throw Exception(errMsg);
    }
}

void QueryResult::setColumnNames(std::vector<std::string> columnNames) {
    this->columnNames = std::move(columnNames);
}

void QueryResult::setColumnTypes(std::vector<LogicalType> columnTypes) {
    this->columnTypes = std::move(columnTypes);
    tuple = std::make_shared<FlatTuple>(this->columnTypes);
}

void QueryResult::addNextResult(std::unique_ptr<QueryResult> next_) {
    nextQueryResult = std::move(next_);
}

std::unique_ptr<QueryResult> QueryResult::moveNextResult() {
    return std::move(nextQueryResult);
}

void QueryResult::setQuerySummary(std::unique_ptr<QuerySummary> summary) {
    querySummary = std::move(summary);
}

void QueryResult::setDBLifeCycleManager(
    std::shared_ptr<DatabaseLifeCycleManager> dbLifeCycleManager) {
    this->dbLifeCycleManager = dbLifeCycleManager;
    if (nextQueryResult) {
        nextQueryResult->setDBLifeCycleManager(dbLifeCycleManager);
    }
}

std::unique_ptr<QueryResult> QueryResult::getQueryResultWithError(const std::string& errorMessage) {
    // TODO(Xiyang): consider introduce error query result class.
    auto queryResult = std::make_unique<MaterializedQueryResult>();
    queryResult->success = false;
    queryResult->errMsg = errorMessage;
    queryResult->nextQueryResult = nullptr;
    queryResult->queryResultIterator = QueryResultIterator{queryResult.get()};
    return queryResult;
}

} // namespace main
} // namespace lbug
