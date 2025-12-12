#pragma once

#include "basic_column_writer.h"
#include "common/constants.h"
#include "common/types/interval_t.h"

namespace lbug {
namespace processor {

class IntervalColumnWriter : public BasicColumnWriter {

public:
    IntervalColumnWriter(ParquetWriter& writer, uint64_t schemaIdx,
        std::vector<std::string> schemaPath, uint64_t maxRepeat, uint64_t maxDefine,
        bool canHaveNulls)
        : BasicColumnWriter(writer, schemaIdx, std::move(schemaPath), maxRepeat, maxDefine,
              canHaveNulls) {}

public:
    static void writeParquetInterval(common::interval_t input, uint8_t* result);

    void writeVector(common::Serializer& bufferedSerializer, ColumnWriterStatistics* state,
        ColumnWriterPageState* pageState, common::ValueVector* vector, uint64_t chunkStart,
        uint64_t chunkEnd) override;

    uint64_t getRowSize(common::ValueVector* /*vector*/, uint64_t /*index*/,
        BasicColumnWriterState& /*state*/) override {
        return common::ParquetConstants::PARQUET_INTERVAL_SIZE;
    }
};

} // namespace processor
} // namespace lbug
