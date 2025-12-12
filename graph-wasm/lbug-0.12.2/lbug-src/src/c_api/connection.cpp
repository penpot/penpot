#include "c_api/lbug.h"
#include "common/exception/exception.h"
#include "main/lbug.h"

namespace lbug {
namespace common {
class Value;
}
} // namespace lbug

using namespace lbug::common;
using namespace lbug::main;

lbug_state lbug_connection_init(lbug_database* database, lbug_connection* out_connection) {
    if (database == nullptr || database->_database == nullptr) {
        out_connection->_connection = nullptr;
        return LbugError;
    }
    try {
        out_connection->_connection = new Connection(static_cast<Database*>(database->_database));
    } catch (Exception& e) {
        out_connection->_connection = nullptr;
        return LbugError;
    }
    return LbugSuccess;
}

void lbug_connection_destroy(lbug_connection* connection) {
    if (connection == nullptr) {
        return;
    }
    if (connection->_connection != nullptr) {
        delete static_cast<Connection*>(connection->_connection);
    }
}

lbug_state lbug_connection_set_max_num_thread_for_exec(lbug_connection* connection,
    uint64_t num_threads) {
    if (connection == nullptr || connection->_connection == nullptr) {
        return LbugError;
    }
    try {
        static_cast<Connection*>(connection->_connection)->setMaxNumThreadForExec(num_threads);
    } catch (Exception& e) {
        return LbugError;
    }
    return LbugSuccess;
}

lbug_state lbug_connection_get_max_num_thread_for_exec(lbug_connection* connection,
    uint64_t* out_result) {
    if (connection == nullptr || connection->_connection == nullptr) {
        return LbugError;
    }
    try {
        *out_result = static_cast<Connection*>(connection->_connection)->getMaxNumThreadForExec();
    } catch (Exception& e) {
        return LbugError;
    }
    return LbugSuccess;
}

lbug_state lbug_connection_query(lbug_connection* connection, const char* query,
    lbug_query_result* out_query_result) {
    if (connection == nullptr || connection->_connection == nullptr) {
        return LbugError;
    }
    try {
        auto query_result =
            static_cast<Connection*>(connection->_connection)->query(query).release();
        if (query_result == nullptr) {
            return LbugError;
        }
        out_query_result->_query_result = query_result;
        out_query_result->_is_owned_by_cpp = false;
        if (!query_result->isSuccess()) {
            return LbugError;
        }
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_connection_prepare(lbug_connection* connection, const char* query,
    lbug_prepared_statement* out_prepared_statement) {
    if (connection == nullptr || connection->_connection == nullptr) {
        return LbugError;
    }
    try {
        auto prepared_statement =
            static_cast<Connection*>(connection->_connection)->prepare(query).release();
        if (prepared_statement == nullptr) {
            return LbugError;
        }
        out_prepared_statement->_prepared_statement = prepared_statement;
        out_prepared_statement->_bound_values =
            new std::unordered_map<std::string, std::unique_ptr<Value>>;
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
    return LbugSuccess;
}

lbug_state lbug_connection_execute(lbug_connection* connection,
    lbug_prepared_statement* prepared_statement, lbug_query_result* out_query_result) {
    if (connection == nullptr || connection->_connection == nullptr ||
        prepared_statement == nullptr || prepared_statement->_prepared_statement == nullptr ||
        prepared_statement->_bound_values == nullptr) {
        return LbugError;
    }
    try {
        auto prepared_statement_ptr =
            static_cast<PreparedStatement*>(prepared_statement->_prepared_statement);
        auto bound_values = static_cast<std::unordered_map<std::string, std::unique_ptr<Value>>*>(
            prepared_statement->_bound_values);

        // Must copy the parameters for safety, and so that the parameters in the prepared statement
        // stay the same.
        std::unordered_map<std::string, std::unique_ptr<Value>> copied_bound_values;
        for (auto& [name, value] : *bound_values) {
            copied_bound_values.emplace(name, value->copy());
        }

        auto query_result =
            static_cast<Connection*>(connection->_connection)
                ->executeWithParams(prepared_statement_ptr, std::move(copied_bound_values))
                .release();
        if (query_result == nullptr) {
            return LbugError;
        }
        out_query_result->_query_result = query_result;
        out_query_result->_is_owned_by_cpp = false;
        if (!query_result->isSuccess()) {
            return LbugError;
        }
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}
void lbug_connection_interrupt(lbug_connection* connection) {
    static_cast<Connection*>(connection->_connection)->interrupt();
}

lbug_state lbug_connection_set_query_timeout(lbug_connection* connection, uint64_t timeout_in_ms) {
    if (connection == nullptr || connection->_connection == nullptr) {
        return LbugError;
    }
    try {
        static_cast<Connection*>(connection->_connection)->setQueryTimeOut(timeout_in_ms);
    } catch (Exception& e) {
        return LbugError;
    }
    return LbugSuccess;
}
