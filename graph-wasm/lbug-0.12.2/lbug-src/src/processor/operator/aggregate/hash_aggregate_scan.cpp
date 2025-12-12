#include "processor/operator/aggregate/hash_aggregate_scan.h"

using namespace lbug::function;

namespace lbug {
namespace processor {

void HashAggregateScan::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    BaseAggregateScan::initLocalStateInternal(resultSet, context);
    for (auto& dataPos : groupByKeyVectorsPos) {
        auto valueVector = resultSet->getValueVector(dataPos);
        groupByKeyVectors.push_back(valueVector.get());
    }
    groupByKeyVectorsColIdxes.resize(groupByKeyVectors.size());
    iota(groupByKeyVectorsColIdxes.begin(), groupByKeyVectorsColIdxes.end(), 0);
}

bool HashAggregateScan::getNextTuplesInternal(ExecutionContext* /*context*/) {
    auto [startOffset, endOffset] = sharedState->getNextRangeToRead();
    if (startOffset >= endOffset) {
        return false;
    }
    auto numRowsToScan = endOffset - startOffset;
    entries.resize(numRowsToScan);
    sharedState->scan(entries, groupByKeyVectors, startOffset, numRowsToScan,
        groupByKeyVectorsColIdxes);
    for (auto pos = 0u; pos < numRowsToScan; ++pos) {
        auto entry = entries[pos];
        auto offset = sharedState->getTableSchema()->getColOffset(groupByKeyVectors.size());
        for (auto i = 0u; i < aggregateVectors.size(); i++) {
            auto vector = aggregateVectors[i];
            auto aggState = reinterpret_cast<AggregateState*>(entry + offset);
            scanInfo.moveAggResultToVectorFuncs[i](*vector, pos, aggState);
            offset += aggState->getStateSize();
        }
    }
    metrics->numOutputTuple.increase(numRowsToScan);
    return true;
}

double HashAggregateScan::getProgress(ExecutionContext* /*context*/) const {
    uint64_t totalNumTuples = sharedState->getNumTuples();
    if (totalNumTuples == 0) {
        return 0.0;
    } else if (sharedState->getCurrentOffset() == totalNumTuples) {
        return 1.0;
    }
    return static_cast<double>(sharedState->getCurrentOffset()) / totalNumTuples;
}

} // namespace processor
} // namespace lbug
