#include "processor/operator/order_by/order_by_scan.h"

#include "common/metric.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void OrderByScanLocalState::init(std::vector<DataPos>& outVectorPos, SortSharedState& sharedState,
    ResultSet& resultSet) {
    for (auto& dataPos : outVectorPos) {
        vectorsToRead.push_back(resultSet.getValueVector(dataPos).get());
    }
    payloadScanner = std::make_unique<PayloadScanner>(sharedState.getMergedKeyBlock(),
        sharedState.getPayloadTables());
    numTuples = 0;
    for (auto& table : sharedState.getPayloadTables()) {
        numTuples += table->getNumTuples();
    }
    numTuplesRead = 0;
}

void OrderByScan::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* /*context*/) {
    localState->init(outVectorPos, *sharedState, *resultSet);
}

bool OrderByScan::getNextTuplesInternal(ExecutionContext* /*context*/) {
    // If there is no more tuples to read, just return false.
    auto numTuplesRead = localState->scan();
    metrics->numOutputTuple.increase(numTuplesRead);
    return numTuplesRead != 0;
}

double OrderByScan::getProgress(ExecutionContext* /*context*/) const {
    if (localState->numTuples == 0) {
        return 0.0;
    } else if (localState->numTuplesRead == localState->numTuples) {
        return 1.0;
    }
    return static_cast<double>(localState->numTuplesRead) / localState->numTuples;
}

} // namespace processor
} // namespace lbug
