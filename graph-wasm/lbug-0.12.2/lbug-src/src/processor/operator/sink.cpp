#include "processor/operator/sink.h"

#include "main/query_result/materialized_query_result.h"
#include "processor/result/factorized_table_util.h"

namespace lbug {
namespace processor {

std::unique_ptr<ResultSet> Sink::getResultSet(storage::MemoryManager* memoryManager) {
    if (resultSetDescriptor == nullptr) {
        // Some pipeline does not need a resultSet, e.g. OrderByMerge
        return std::unique_ptr<ResultSet>();
    }
    return std::make_unique<ResultSet>(resultSetDescriptor.get(), memoryManager);
}

std::unique_ptr<main::QueryResult> SimpleSink::getQueryResult() const {
    return std::make_unique<main::MaterializedQueryResult>(messageTable);
}

void SimpleSink::appendMessage(const std::string& msg, storage::MemoryManager* memoryManager) {
    FactorizedTableUtils::appendStringToTable(messageTable.get(), msg, memoryManager);
}

} // namespace processor
} // namespace lbug
