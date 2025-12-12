#include "processor/operator/aggregate/simple_aggregate_scan.h"

namespace lbug {
namespace processor {

void SimpleAggregateScan::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    BaseAggregateScan::initLocalStateInternal(resultSet, context);
    KU_ASSERT(!scanInfo.aggregatesPos.empty());
    auto outDataChunkPos = scanInfo.aggregatesPos[0].dataChunkPos;
    RUNTIME_CHECK({
        for (auto& dataPos : scanInfo.aggregatesPos) {
            KU_ASSERT(dataPos.dataChunkPos == outDataChunkPos);
        }
    });
    outDataChunk = resultSet->dataChunks[outDataChunkPos].get();
}

bool SimpleAggregateScan::getNextTuplesInternal(ExecutionContext* /*context*/) {
    auto [startOffset, endOffset] = sharedState->getNextRangeToRead();
    if (startOffset >= endOffset) {
        return false;
    }
    // Output of simple aggregate is guaranteed to be a single value for each aggregate.
    KU_ASSERT(startOffset == 0 && endOffset == 1);
    for (auto i = 0u; i < aggregateVectors.size(); i++) {
        scanInfo.moveAggResultToVectorFuncs[i](*aggregateVectors[i], 0 /* position to write */,
            sharedState->getAggregateState(i));
    }
    KU_ASSERT(!scanInfo.aggregatesPos.empty());
    outDataChunk->state->initOriginalAndSelectedSize(1);
    metrics->numOutputTuple.increase(outDataChunk->state->getSelVector().getSelSize());
    return true;
}

} // namespace processor
} // namespace lbug
