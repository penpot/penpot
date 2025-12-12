#include "processor/operator/persistent/writer/parquet/boolean_column_writer.h"

#include "common/serializer/serializer.h"

namespace lbug {
namespace processor {

void BooleanColumnWriter::writeVector(common::Serializer& temp_writer,
    ColumnWriterStatistics* writerStatistics, ColumnWriterPageState* writerPageState,
    common::ValueVector* vector, uint64_t chunkStart, uint64_t chunkEnd) {
    auto stats = reinterpret_cast<BooleanStatisticsState*>(writerStatistics);
    auto state = reinterpret_cast<BooleanWriterPageState*>(writerPageState);
    for (auto r = chunkStart; r < chunkEnd; r++) {
        auto pos = getVectorPos(vector, r);
        if (!vector->isNull(pos)) {
            // only encode if non-null
            if (vector->getValue<bool>(pos)) {
                stats->max = true;
                state->byte |= 1 << state->bytePos;
            } else {
                stats->min = false;
            }
            state->bytePos++;

            if (state->bytePos == 8) {
                temp_writer.write<uint8_t>(state->byte);
                state->byte = 0;
                state->bytePos = 0;
            }
        }
    }
}

void BooleanColumnWriter::flushPageState(common::Serializer& temp_writer,
    ColumnWriterPageState* writerPageState) {
    auto state = reinterpret_cast<BooleanWriterPageState*>(writerPageState);
    if (state->bytePos > 0) {
        temp_writer.write<uint8_t>(state->byte);
        state->byte = 0;
        state->bytePos = 0;
    }
}

} // namespace processor
} // namespace lbug
