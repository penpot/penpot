#pragma once

#include "basic_column_writer.h"
#include "common/constants.h"

namespace lbug {
namespace processor {

class UUIDColumnWriter : public BasicColumnWriter {
public:
    UUIDColumnWriter(ParquetWriter& writer, uint64_t schemaIdx, std::vector<std::string> schemaPath,
        uint64_t maxRepeat, uint64_t maxDefine, bool canHaveNulls)
        : BasicColumnWriter(writer, schemaIdx, std::move(schemaPath), maxRepeat, maxDefine,
              canHaveNulls) {}

public:
    void writeVector(common::Serializer& bufferedSerializer, ColumnWriterStatistics* state,
        ColumnWriterPageState* pageState, common::ValueVector* vector, uint64_t chunkStart,
        uint64_t chunkEnd) override;

    uint64_t getRowSize(common::ValueVector* /*vector*/, uint64_t /*index*/,
        BasicColumnWriterState& /*state*/) override {
        return common::ParquetConstants::PARQUET_UUID_SIZE;
    }
};

} // namespace processor
} // namespace lbug
