#include "processor/operator/skip.h"

#include "processor/execution_context.h"

namespace lbug {
namespace processor {

void Skip::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* /*context*/) {
    dataChunkToSelect = resultSet->dataChunks[dataChunkToSelectPos];
}

std::string SkipPrintInfo::toString() const {
    std::string result = "Skip: ";
    result += std::to_string(number);
    return result;
}

bool Skip::getNextTuplesInternal(ExecutionContext* context) {
    auto numTupleSkippedBefore = 0u;
    auto numTuplesAvailable = 1u;
    do {
        restoreSelVector(*dataChunkToSelect->state);
        // end of execution due to no more input
        if (!children[0]->getNextTuple(context)) {
            return false;
        }
        saveSelVector(*dataChunkToSelect->state);
        numTuplesAvailable = resultSet->getNumTuples(dataChunksPosInScope);
        numTupleSkippedBefore = counter->fetch_add(numTuplesAvailable);
    } while (numTupleSkippedBefore + numTuplesAvailable <= skipNumber);
    auto numTupleToSkipInCurrentResultSet = (int64_t)(skipNumber - numTupleSkippedBefore);
    if (numTupleToSkipInCurrentResultSet <= 0) {
        // Other thread has finished skipping. Process everything in current result set.
        metrics->numOutputTuple.increase(numTuplesAvailable);
    } else {
        // If all dataChunks are flat, numTupleAvailable = 1 which means numTupleSkippedBefore =
        // skipNumber. So execution is handled in above if statement.
        KU_ASSERT(!dataChunkToSelect->state->isFlat());
        auto buffer = dataChunkToSelect->state->getSelVectorUnsafe().getMutableBuffer();
        if (dataChunkToSelect->state->getSelVector().isUnfiltered()) {
            for (uint64_t i = numTupleToSkipInCurrentResultSet;
                 i < dataChunkToSelect->state->getSelVector().getSelSize(); ++i) {
                buffer[i - numTupleToSkipInCurrentResultSet] = i;
            }
            dataChunkToSelect->state->getSelVectorUnsafe().setToFiltered();
        } else {
            for (uint64_t i = numTupleToSkipInCurrentResultSet;
                 i < dataChunkToSelect->state->getSelVector().getSelSize(); ++i) {
                buffer[i - numTupleToSkipInCurrentResultSet] = buffer[i];
            }
        }
        dataChunkToSelect->state->getSelVectorUnsafe().setSelSize(
            dataChunkToSelect->state->getSelVector().getSelSize() -
            numTupleToSkipInCurrentResultSet);
        metrics->numOutputTuple.increase(dataChunkToSelect->state->getSelVector().getSelSize());
    }
    return true;
}

} // namespace processor
} // namespace lbug
