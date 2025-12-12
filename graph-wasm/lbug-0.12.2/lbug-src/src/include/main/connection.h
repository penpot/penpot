#pragma once

#include "client_context.h"
#include "database.h"
#include "function/udf_function.h"

namespace lbug {
namespace main {

/**
 * @brief Connection is used to interact with a Database instance. Each Connection is thread-safe.
 * Multiple connections can connect to the same Database instance in a multi-threaded environment.
 */
class Connection {
    friend class testing::BaseGraphTest;
    friend class testing::PrivateGraphTest;
    friend class testing::TestHelper;
    friend class benchmark::Benchmark;
    friend class ConnectionExecuteAsyncWorker;
    friend class ConnectionQueryAsyncWorker;

public:
    /**
     * @brief Creates a connection to the database.
     * @param database A pointer to the database instance that this connection will be connected to.
     */
    LBUG_API explicit Connection(Database* database);
    /**
     * @brief Destructs the connection.
     */
    LBUG_API ~Connection();
    /**
     * @brief Sets the maximum number of threads to use for execution in the current connection.
     * @param numThreads The number of threads to use for execution in the current connection.
     */
    LBUG_API void setMaxNumThreadForExec(uint64_t numThreads);
    /**
     * @brief Returns the maximum number of threads to use for execution in the current connection.
     * @return the maximum number of threads to use for execution in the current connection.
     */
    LBUG_API uint64_t getMaxNumThreadForExec();

    /**
     * @brief Executes the given query and returns the result.
     * @param query The query to execute.
     * @return the result of the query.
     */
    LBUG_API std::unique_ptr<QueryResult> query(std::string_view query);

    LBUG_API std::unique_ptr<QueryResult> queryAsArrow(std::string_view query, int64_t chunkSize);

    /**
     * @brief Prepares the given query and returns the prepared statement.
     * @param query The query to prepare.
     * @return the prepared statement.
     */
    LBUG_API std::unique_ptr<PreparedStatement> prepare(std::string_view query);

    /**
     * @brief Prepares the given query and returns the prepared statement.
     * @param query The query to prepare.
     * @param inputParams The parameter pack where each arg is a pair with the first element
     * being parameter name and second element being parameter value. The only parameters that are
     * relevant during prepare are ones that will be substituted with a scan source. Any other
     * parameters will either be ignored or will cause an error to be thrown.
     * @return the prepared statement.
     */
    LBUG_API std::unique_ptr<PreparedStatement> prepareWithParams(std::string_view query,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> inputParams);

    /**
     * @brief Executes the given prepared statement with args and returns the result.
     * @param preparedStatement The prepared statement to execute.
     * @param args The parameter pack where each arg is a std::pair with the first element being
     * parameter name and second element being parameter value.
     * @return the result of the query.
     */
    template<typename... Args>
    inline std::unique_ptr<QueryResult> execute(PreparedStatement* preparedStatement,
        std::pair<std::string, Args>... args) {
        std::unordered_map<std::string, std::unique_ptr<common::Value>> inputParameters;
        return executeWithParams(preparedStatement, std::move(inputParameters), args...);
    }
    /**
     * @brief Executes the given prepared statement with inputParams and returns the result.
     * @param preparedStatement The prepared statement to execute.
     * @param inputParams The parameter pack where each arg is a std::pair with the first element
     * being parameter name and second element being parameter value.
     * @return the result of the query.
     */
    LBUG_API std::unique_ptr<QueryResult> executeWithParams(PreparedStatement* preparedStatement,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> inputParams);
    /**
     * @brief interrupts all queries currently executing within this connection.
     */
    LBUG_API void interrupt();

    /**
     * @brief sets the query timeout value of the current connection. A value of zero (the default)
     * disables the timeout.
     */
    LBUG_API void setQueryTimeOut(uint64_t timeoutInMS);

    template<typename TR, typename... Args>
    void createScalarFunction(std::string name, TR (*udfFunc)(Args...)) {
        addScalarFunction(name, function::UDF::getFunction<TR, Args...>(name, udfFunc));
    }

    template<typename TR, typename... Args>
    void createScalarFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypes,
        common::LogicalTypeID returnType, TR (*udfFunc)(Args...)) {
        addScalarFunction(name, function::UDF::getFunction<TR, Args...>(name, udfFunc,
                                    std::move(parameterTypes), returnType));
    }

    void addUDFFunctionSet(std::string name, function::function_set func) {
        addScalarFunction(name, std::move(func));
    }

    void removeUDFFunction(std::string name) { removeScalarFunction(name); }

    template<typename TR, typename... Args>
    void createVectorizedFunction(std::string name, function::scalar_func_exec_t scalarFunc) {
        addScalarFunction(name,
            function::UDF::getVectorizedFunction<TR, Args...>(name, std::move(scalarFunc)));
    }

    void createVectorizedFunction(std::string name,
        std::vector<common::LogicalTypeID> parameterTypes, common::LogicalTypeID returnType,
        function::scalar_func_exec_t scalarFunc) {
        addScalarFunction(name, function::UDF::getVectorizedFunction(name, std::move(scalarFunc),
                                    std::move(parameterTypes), returnType));
    }

    ClientContext* getClientContext() { return clientContext.get(); };

private:
    template<typename T, typename... Args>
    std::unique_ptr<QueryResult> executeWithParams(PreparedStatement* preparedStatement,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> params,
        std::pair<std::string, T> arg, std::pair<std::string, Args>... args) {
        return clientContext->executeWithParams(preparedStatement, std::move(params), arg, args...);
    }

    LBUG_API void addScalarFunction(std::string name, function::function_set definitions);
    LBUG_API void removeScalarFunction(std::string name);

    std::unique_ptr<QueryResult> queryWithID(std::string_view query, uint64_t queryID);

    std::unique_ptr<QueryResult> executeWithParamsWithID(PreparedStatement* preparedStatement,
        std::unordered_map<std::string, std::unique_ptr<common::Value>> inputParams,
        uint64_t queryID);

private:
    Database* database;
    std::unique_ptr<ClientContext> clientContext;
    std::shared_ptr<common::DatabaseLifeCycleManager> dbLifeCycleManager;
};

} // namespace main
} // namespace lbug
