#include "main/query_result.h"

#include "c_api/helpers.h"
#include "c_api/lbug.h"

using namespace lbug::main;
using namespace lbug::common;
using namespace lbug::processor;

void lbug_query_result_destroy(lbug_query_result* query_result) {
    if (query_result == nullptr) {
        return;
    }
    if (query_result->_query_result != nullptr) {
        if (!query_result->_is_owned_by_cpp) {
            delete static_cast<QueryResult*>(query_result->_query_result);
        }
    }
}

bool lbug_query_result_is_success(lbug_query_result* query_result) {
    return static_cast<QueryResult*>(query_result->_query_result)->isSuccess();
}

char* lbug_query_result_get_error_message(lbug_query_result* query_result) {
    auto error_message = static_cast<QueryResult*>(query_result->_query_result)->getErrorMessage();
    if (error_message.empty()) {
        return nullptr;
    }
    return convertToOwnedCString(error_message);
}

uint64_t lbug_query_result_get_num_columns(lbug_query_result* query_result) {
    return static_cast<QueryResult*>(query_result->_query_result)->getNumColumns();
}

lbug_state lbug_query_result_get_column_name(lbug_query_result* query_result, uint64_t index,
    char** out_column_name) {
    auto column_names = static_cast<QueryResult*>(query_result->_query_result)->getColumnNames();
    if (index >= column_names.size()) {
        return LbugError;
    }
    *out_column_name = convertToOwnedCString(column_names[index]);
    return LbugSuccess;
}

lbug_state lbug_query_result_get_column_data_type(lbug_query_result* query_result, uint64_t index,
    lbug_logical_type* out_column_data_type) {
    auto column_data_types =
        static_cast<QueryResult*>(query_result->_query_result)->getColumnDataTypes();
    if (index >= column_data_types.size()) {
        return LbugError;
    }
    const auto& column_data_type = column_data_types[index];
    out_column_data_type->_data_type = new LogicalType(column_data_type.copy());
    return LbugSuccess;
}

uint64_t lbug_query_result_get_num_tuples(lbug_query_result* query_result) {
    return static_cast<QueryResult*>(query_result->_query_result)->getNumTuples();
}

lbug_state lbug_query_result_get_query_summary(lbug_query_result* query_result,
    lbug_query_summary* out_query_summary) {
    if (out_query_summary == nullptr) {
        return LbugError;
    }
    auto query_summary = static_cast<QueryResult*>(query_result->_query_result)->getQuerySummary();
    out_query_summary->_query_summary = query_summary;
    return LbugSuccess;
}

bool lbug_query_result_has_next(lbug_query_result* query_result) {
    return static_cast<QueryResult*>(query_result->_query_result)->hasNext();
}

bool lbug_query_result_has_next_query_result(lbug_query_result* query_result) {
    return static_cast<QueryResult*>(query_result->_query_result)->hasNextQueryResult();
}

lbug_state lbug_query_result_get_next_query_result(lbug_query_result* query_result,
    lbug_query_result* out_query_result) {
    if (!lbug_query_result_has_next_query_result(query_result)) {
        return LbugError;
    }
    auto next_query_result =
        static_cast<QueryResult*>(query_result->_query_result)->getNextQueryResult();
    if (next_query_result == nullptr) {
        return LbugError;
    }
    out_query_result->_query_result = next_query_result;
    out_query_result->_is_owned_by_cpp = true;
    return LbugSuccess;
}

lbug_state lbug_query_result_get_next(lbug_query_result* query_result,
    lbug_flat_tuple* out_flat_tuple) {
    try {
        auto flat_tuple = static_cast<QueryResult*>(query_result->_query_result)->getNext();
        out_flat_tuple->_flat_tuple = flat_tuple.get();
        out_flat_tuple->_is_owned_by_cpp = true;
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

char* lbug_query_result_to_string(lbug_query_result* query_result) {
    std::string result_string = static_cast<QueryResult*>(query_result->_query_result)->toString();
    return convertToOwnedCString(result_string);
}

void lbug_query_result_reset_iterator(lbug_query_result* query_result) {
    static_cast<QueryResult*>(query_result->_query_result)->resetIterator();
}

lbug_state lbug_query_result_get_arrow_schema(lbug_query_result* query_result,
    ArrowSchema* out_schema) {
    try {
        *out_schema = *static_cast<QueryResult*>(query_result->_query_result)->getArrowSchema();
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}

lbug_state lbug_query_result_get_next_arrow_chunk(lbug_query_result* query_result,
    int64_t chunk_size, ArrowArray* out_arrow_array) {
    try {
        *out_arrow_array =
            *static_cast<QueryResult*>(query_result->_query_result)->getNextArrowChunk(chunk_size);
        return LbugSuccess;
    } catch (Exception& e) {
        return LbugError;
    }
}
