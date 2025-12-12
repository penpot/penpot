#include "processor/operator/persistent/writer/parquet/interval_column_writer.h"

#include "common/exception/runtime.h"
#include "common/serializer/serializer.h"

namespace lbug {
namespace processor {

void IntervalColumnWriter::writeParquetInterval(common::interval_t input, uint8_t* result) {
    if (input.days < 0 || input.months < 0 || input.micros < 0) {
        throw common::RuntimeException{"Parquet files do not support negative intervals"};
    }
    uint32_t dataToStore = 0;
    dataToStore = input.months;
    memcpy(result, &dataToStore, sizeof(dataToStore));
    dataToStore = input.days;
    result += sizeof(dataToStore);
    memcpy(result, &dataToStore, sizeof(dataToStore));
    dataToStore = input.micros / 1000;
    result += sizeof(dataToStore);
    memcpy(result, &dataToStore, sizeof(dataToStore));
}

void IntervalColumnWriter::writeVector(common::Serializer& bufferedSerializer,
    ColumnWriterStatistics* /*state*/, ColumnWriterPageState* /*pageState*/,
    common::ValueVector* vector, uint64_t chunkStart, uint64_t chunkEnd) {
    uint8_t tmpIntervalBuf[common::ParquetConstants::PARQUET_INTERVAL_SIZE];
    for (auto r = chunkStart; r < chunkEnd; r++) {
        auto pos = getVectorPos(vector, r);
        if (!vector->isNull(pos)) {
            writeParquetInterval(vector->getValue<common::interval_t>(pos), tmpIntervalBuf);
            bufferedSerializer.write(tmpIntervalBuf,
                common::ParquetConstants::PARQUET_INTERVAL_SIZE);
        }
    }
}

} // namespace processor
} // namespace lbug
