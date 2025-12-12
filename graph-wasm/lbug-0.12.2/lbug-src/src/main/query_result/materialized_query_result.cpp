#include "main/query_result/materialized_query_result.h"

#include "common/arrow/arrow_row_batch.h"
#include "common/exception/runtime.h"
#include "processor/result/factorized_table.h"
#include "processor/result/flat_tuple.h"

using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace main {

MaterializedQueryResult::MaterializedQueryResult() = default;

MaterializedQueryResult::MaterializedQueryResult(std::shared_ptr<FactorizedTable> table)
    : QueryResult{type_}, table{std::move(table)} {
    iterator = std::make_unique<FactorizedTableIterator>(*this->table);
}

MaterializedQueryResult::MaterializedQueryResult(std::vector<std::string> columnNames,
    std::vector<LogicalType> columnTypes, std::shared_ptr<FactorizedTable> table)
    : QueryResult{type_, std::move(columnNames), std::move(columnTypes)}, table{std::move(table)} {
    iterator = std::make_unique<FactorizedTableIterator>(*this->table);
}

MaterializedQueryResult::~MaterializedQueryResult() {
    if (!dbLifeCycleManager) {
        return;
    }
    if (!table) {
        return;
    }
    table->setPreventDestruction(dbLifeCycleManager->isDatabaseClosed);
}

uint64_t MaterializedQueryResult::getNumTuples() const {
    checkDatabaseClosedOrThrow();
    validateQuerySucceed();
    return table->getTotalNumFlatTuples();
}

bool MaterializedQueryResult::hasNext() const {
    checkDatabaseClosedOrThrow();
    validateQuerySucceed();
    return iterator->hasNext();
}

std::shared_ptr<FlatTuple> MaterializedQueryResult::getNext() {
    checkDatabaseClosedOrThrow();
    validateQuerySucceed();
    if (!hasNext()) {
        throw RuntimeException(
            "No more tuples in QueryResult, Please check hasNext() before calling getNext().");
    }
    iterator->getNext(*tuple);
    return tuple;
}

void MaterializedQueryResult::resetIterator() {
    checkDatabaseClosedOrThrow();
    validateQuerySucceed();
    iterator->resetState();
}

std::string MaterializedQueryResult::toString() const {
    checkDatabaseClosedOrThrow();
    if (!isSuccess()) {
        return errMsg;
    }
    std::string result;
    // print header
    for (auto i = 0u; i < columnNames.size(); ++i) {
        if (i != 0) {
            result += "|";
        }
        result += columnNames[i];
    }
    result += "\n";
    auto tuple_ = FlatTuple(this->columnTypes);
    auto iterator_ = FactorizedTableIterator(*table);
    while (iterator->hasNext()) {
        iterator->getNext(tuple_);
        result += tuple_.toString();
    }
    return result;
}

bool MaterializedQueryResult::hasNextArrowChunk() {
    return hasNext();
}

std::unique_ptr<ArrowArray> MaterializedQueryResult::getNextArrowChunk(int64_t chunkSize) {
    checkDatabaseClosedOrThrow();
    auto rowBatch =
        std::make_unique<ArrowRowBatch>(columnTypes, chunkSize, false /* fallbackExtensionTypes */);
    auto rowBatchSize = 0u;
    while (rowBatchSize < chunkSize) {
        if (!iterator->hasNext()) {
            break;
        }
        (void)iterator->getNext(*tuple);
        rowBatch->append(*tuple);
        rowBatchSize++;
    }
    return std::make_unique<ArrowArray>(rowBatch->toArray(columnTypes));
}

} // namespace main
} // namespace lbug
