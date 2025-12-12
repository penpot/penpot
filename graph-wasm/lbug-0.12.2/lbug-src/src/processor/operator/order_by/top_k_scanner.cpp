#include "processor/operator/order_by/top_k_scanner.h"

namespace lbug {
namespace processor {

void TopKLocalScanState::init(std::vector<DataPos>& outVectorPos, TopKSharedState& sharedState,
    ResultSet& resultSet) {
    for (auto& pos : outVectorPos) {
        vectorsToScan.push_back(resultSet.getValueVector(pos).get());
    }
    payloadScanner = sharedState.buffer->getScanner();
}

void TopKScan::initLocalStateInternal(lbug::processor::ResultSet* resultSet,
    lbug::processor::ExecutionContext* /*context*/) {
    localState->init(outVectorPos, *sharedState, *resultSet);
}

bool TopKScan::getNextTuplesInternal(ExecutionContext* /*context*/) {
    auto numTuplesRead = localState->scan();
    metrics->numOutputTuple.increase(numTuplesRead);
    return numTuplesRead != 0;
}

} // namespace processor
} // namespace lbug
