#include "processor/operator/persistent/writer/parquet/uuid_column_writer.h"

#include "common/constants.h"
#include "common/serializer/serializer.h"
#include "common/types/uuid.h"

namespace lbug {
namespace processor {

static void writeParquetUUID(common::ku_uuid_t input, uint8_t* result) {
    uint64_t high_bytes = input.value.high ^ (int64_t(1) << 63);
    uint64_t low_bytes = input.value.low;

    for (auto i = 0u; i < sizeof(uint64_t); i++) {
        auto shift_count = (sizeof(uint64_t) - i - 1) * 8;
        result[i] = (high_bytes >> shift_count) & 0xFF;
    }
    for (auto i = 0u; i < sizeof(uint64_t); i++) {
        auto shift_count = (sizeof(uint64_t) - i - 1) * 8;
        result[sizeof(uint64_t) + i] = (low_bytes >> shift_count) & 0xFF;
    }
}

void UUIDColumnWriter::writeVector(common::Serializer& bufferedSerializer,
    ColumnWriterStatistics* /*state*/, ColumnWriterPageState* /*pageState*/,
    common::ValueVector* vector, uint64_t chunkStart, uint64_t chunkEnd) {
    uint8_t buffer[common::ParquetConstants::PARQUET_UUID_SIZE];
    for (auto i = chunkStart; i < chunkEnd; i++) {
        auto pos = getVectorPos(vector, i);
        if (!vector->isNull(pos)) {
            writeParquetUUID(vector->getValue<common::ku_uuid_t>(pos), buffer);
            bufferedSerializer.write(buffer, common::ParquetConstants::PARQUET_UUID_SIZE);
        }
    }
}

} // namespace processor
} // namespace lbug
