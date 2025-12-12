#include "main/query_summary.h"

#include <cstdlib>

#include "c_api/lbug.h"

using namespace lbug::main;

void lbug_query_summary_destroy(lbug_query_summary* query_summary) {
    if (query_summary == nullptr) {
        return;
    }
    // The query summary is owned by the query result, so it should not be deleted here.
    query_summary->_query_summary = nullptr;
}

double lbug_query_summary_get_compiling_time(lbug_query_summary* query_summary) {
    return static_cast<QuerySummary*>(query_summary->_query_summary)->getCompilingTime();
}

double lbug_query_summary_get_execution_time(lbug_query_summary* query_summary) {
    return static_cast<QuerySummary*>(query_summary->_query_summary)->getExecutionTime();
}
