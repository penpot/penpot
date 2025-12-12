#include "processor/operator/cross_product.h"

#include "common/metric.h"

namespace lbug {
namespace processor {

void CrossProduct::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* /*context*/) {
    for (auto& pos : info.outVecPos) {
        vectorsToScan.push_back(resultSet->getValueVector(pos).get());
    }
    localState.init();
}

bool CrossProduct::getNextTuplesInternal(ExecutionContext* context) {
    // Note: we should NOT morselize right table scanning (i.e. calling sharedState.getMorsel)
    // because every thread should scan its own table.
    auto table = localState.table.get();
    if (table->getNumTuples() == 0) {
        return false;
    }
    if (localState.startIdx == table->getNumTuples()) { // no more to scan from right
        if (!children[0]->getNextTuple(context)) {      // fetch a new left tuple
            return false;
        }
        localState.startIdx = 0; // reset right table scanning for a new left tuple
    }
    // scan from right table if there is tuple left
    auto numTuplesToScan =
        std::min(localState.maxMorselSize, table->getNumTuples() - localState.startIdx);
    table->scan(vectorsToScan, localState.startIdx, numTuplesToScan, info.colIndicesToScan);
    localState.startIdx += numTuplesToScan;
    metrics->numOutputTuple.increase(numTuplesToScan);
    return true;
}

} // namespace processor
} // namespace lbug
