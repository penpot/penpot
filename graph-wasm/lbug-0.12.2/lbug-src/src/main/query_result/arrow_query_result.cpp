#include "main/query_result/arrow_query_result.h"

#include "common/arrow/arrow_row_batch.h"
#include "common/exception/not_implemented.h"
#include "common/exception/runtime.h"
#include "processor/result/factorized_table.h"

using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace main {

ArrowQueryResult::ArrowQueryResult(std::vector<ArrowArray> arrays, int64_t chunkSize)
    : QueryResult{type_}, arrays{std::move(arrays)}, chunkSize_{chunkSize} {
    for (auto& array : this->arrays) {
        numTuples += array.length;
    }
}

ArrowQueryResult::ArrowQueryResult(std::vector<std::string> columnNames,
    std::vector<LogicalType> columnTypes, FactorizedTable& table, int64_t chunkSize)
    : QueryResult{type_, std::move(columnNames), std::move(columnTypes)}, chunkSize_{chunkSize} {
    auto iterator = FactorizedTableIterator(table);
    while (iterator.hasNext()) {
        arrays.push_back(getArray(iterator, chunkSize));
    }
}

uint64_t ArrowQueryResult::getNumTuples() const {
    return numTuples;
}

ArrowArray ArrowQueryResult::getArray(FactorizedTableIterator& iterator, int64_t chunkSize) {
    auto rowBatch = ArrowRowBatch(columnTypes, chunkSize, false /* fallbackExtensionTypes */);
    auto rowBatchSize = 0u;
    while (rowBatchSize < chunkSize) {
        if (!iterator.hasNext()) {
            break;
        }
        (void)iterator.getNext(*tuple);
        rowBatch.append(*tuple);
        rowBatchSize++;
        numTuples++;
    }
    return rowBatch.toArray(columnTypes);
}

bool ArrowQueryResult::hasNext() const {
    throw NotImplementedException(
        "ArrowQueryResult does not implement hasNext. Use MaterializedQueryResult instead.");
}

std::shared_ptr<FlatTuple> ArrowQueryResult::getNext() {
    throw NotImplementedException(
        "ArrowQueryResult does not implement getNext. Use MaterializedQueryResult instead.");
}

void ArrowQueryResult::resetIterator() {
    cursor = 0u;
}

std::string ArrowQueryResult::toString() const {
    throw NotImplementedException(
        "ArrowQueryResult does not implement toString. Use MaterializedQueryResult instead.");
}

bool ArrowQueryResult::hasNextArrowChunk() {
    return cursor < arrays.size();
}

std::unique_ptr<ArrowArray> ArrowQueryResult::getNextArrowChunk(int64_t chunkSize) {
    if (chunkSize != chunkSize_) {
        throw RuntimeException(
            stringFormat("Chunk size does not match expected value {}.", chunkSize_));
    }
    return std::make_unique<ArrowArray>(arrays[cursor++]);
}

} // namespace main
} // namespace lbug
