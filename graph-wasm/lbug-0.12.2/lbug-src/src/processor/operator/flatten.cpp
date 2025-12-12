#include "processor/operator/flatten.h"

#include "common/metric.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void Flatten::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* /*context*/) {
    dataChunkState = resultSet->dataChunks[dataChunkToFlattenPos]->state.get();
    currentSelVector->setToFiltered(1 /* size */);
    localState = std::make_unique<FlattenLocalState>();
}

bool Flatten::getNextTuplesInternal(ExecutionContext* context) {
    if (localState->currentIdx == localState->sizeToFlatten) {
        dataChunkState->setToUnflat(); // TODO(Xiyang): this should be part of restore/save
        restoreSelVector(*dataChunkState);
        if (!children[0]->getNextTuple(context)) {
            return false;
        }
        localState->currentIdx = 0;
        localState->sizeToFlatten = dataChunkState->getSelVector().getSelSize();
        saveSelVector(*dataChunkState);
        dataChunkState->setToFlat();
    }
    sel_t selPos = prevSelVector->operator[](localState->currentIdx++);
    currentSelVector->operator[](0) = selPos;
    metrics->numOutputTuple.incrementByOne();
    return true;
}

void Flatten::resetCurrentSelVector(const SelectionVector&) {}

} // namespace processor
} // namespace lbug
