#pragma once

#include <string>

#include "common/api.h"
#include "common/arrow/arrow.h"
#include "common/database_lifecycle_manager.h"
#include "common/types/types.h"
#include "query_summary.h"

namespace lbug {
namespace processor {
class FlatTuple;
}
namespace main {

enum class QueryResultType {
    FTABLE = 0,
    ARROW = 1,
};

/**
 * @brief QueryResult stores the result of a query execution.
 */
class QueryResult {
public:
    /**
     * @brief Used to create a QueryResult object for the failing query.
     */
    LBUG_API QueryResult();
    explicit QueryResult(QueryResultType type);
    QueryResult(QueryResultType type, std::vector<std::string> columnNames,
        std::vector<common::LogicalType> columnTypes);

    /**
     * @brief Deconstructs the QueryResult object.
     */
    LBUG_API virtual ~QueryResult() = 0;
    /**
     * @return if the query is executed successfully or not.
     */
    LBUG_API bool isSuccess() const;
    /**
     * @return error message of the query execution if the query fails.
     */
    LBUG_API std::string getErrorMessage() const;
    /**
     * @return number of columns in query result.
     */
    LBUG_API size_t getNumColumns() const;
    /**
     * @return name of each column in the query result.
     */
    LBUG_API std::vector<std::string> getColumnNames() const;
    /**
     * @return dataType of each column in the query result.
     */
    LBUG_API std::vector<common::LogicalType> getColumnDataTypes() const;
    /**
     * @return query summary which stores the execution time, compiling time, plan and query
     * options.
     */
    LBUG_API QuerySummary* getQuerySummary() const;
    QuerySummary* getQuerySummaryUnsafe();
    /**
     * @return whether there are more query results to read.
     */
    LBUG_API bool hasNextQueryResult() const;
    /**
     * @return get the next query result to read (for multiple query statements).
     */
    LBUG_API QueryResult* getNextQueryResult();
    /**
     * @return num of tuples in query result.
     */
    LBUG_API virtual uint64_t getNumTuples() const = 0;
    /**
     * @return whether there are more tuples to read.
     */
    LBUG_API virtual bool hasNext() const = 0;
    /**
     * @return next flat tuple in the query result. Note that to reduce resource allocation, all
     * calls to getNext() reuse the same FlatTuple object. Since its contents will be overwritten,
     * please complete processing a FlatTuple or make a copy of its data before calling getNext()
     * again.
     */
    LBUG_API virtual std::shared_ptr<processor::FlatTuple> getNext() = 0;
    /**
     * @brief Resets the result tuple iterator.
     */
    LBUG_API virtual void resetIterator() = 0;
    /**
     * @return string of first query result.
     */
    LBUG_API virtual std::string toString() const = 0;
    /**
     * @brief Returns the arrow schema of the query result.
     * @return datatypes of the columns as an arrow schema
     *
     * It is the caller's responsibility to call the release function to release the underlying data
     * If converting to another arrow type, this is usually handled automatically.
     */
    LBUG_API std::unique_ptr<ArrowSchema> getArrowSchema() const;
    /**
     * @return whether there are more arrow chunk to read.
     */
    LBUG_API virtual bool hasNextArrowChunk() = 0;
    /**
     * @brief Returns the next chunk of the query result as an arrow array.
     * @param chunkSize number of tuples to return in the chunk.
     * @return An arrow array representation of the next chunkSize tuples of the query result.
     *
     * The ArrowArray internally stores an arrow struct with fields for each of the columns.
     * This can be converted to a RecordBatch with arrow's ImportRecordBatch function
     *
     * It is the caller's responsibility to call the release function to release the underlying data
     * If converting to another arrow type, this is usually handled automatically.
     */
    LBUG_API virtual std::unique_ptr<ArrowArray> getNextArrowChunk(int64_t chunkSize) = 0;

    QueryResultType getType() const { return type; }

    void setColumnNames(std::vector<std::string> columnNames);
    void setColumnTypes(std::vector<common::LogicalType> columnTypes);

    void addNextResult(std::unique_ptr<QueryResult> next_);
    std::unique_ptr<QueryResult> moveNextResult();

    void setQuerySummary(std::unique_ptr<QuerySummary> summary);

    void setDBLifeCycleManager(
        std::shared_ptr<common::DatabaseLifeCycleManager> dbLifeCycleManager);

    static std::unique_ptr<QueryResult> getQueryResultWithError(const std::string& errorMessage);

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

protected:
    void validateQuerySucceed() const;
    void checkDatabaseClosedOrThrow() const;

protected:
    class QueryResultIterator {
    public:
        QueryResultIterator() = default;

        explicit QueryResultIterator(QueryResult* startResult) : current(startResult) {}

        void operator++() {
            if (current) {
                current = current->nextQueryResult.get();
            }
        }

        bool isEnd() const { return current == nullptr; }

        bool hasNextQueryResult() const { return current->nextQueryResult != nullptr; }

        QueryResult* getCurrentResult() const { return current; }

    private:
        QueryResult* current;
    };

    QueryResultType type;

    bool success = true;

    std::string errMsg;

    std::vector<std::string> columnNames;

    std::vector<common::LogicalType> columnTypes;

    std::shared_ptr<processor::FlatTuple> tuple;

    std::unique_ptr<QuerySummary> querySummary;

    std::unique_ptr<QueryResult> nextQueryResult;

    QueryResultIterator queryResultIterator;

    std::shared_ptr<common::DatabaseLifeCycleManager> dbLifeCycleManager;
};

} // namespace main
} // namespace lbug
