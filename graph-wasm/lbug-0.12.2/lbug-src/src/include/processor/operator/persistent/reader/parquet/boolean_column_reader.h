#pragma once

#include "column_reader.h"
#include "templated_column_reader.h"

namespace lbug {
namespace processor {

struct BooleanParquetValueConversion;

class BooleanColumnReader : public TemplatedColumnReader<bool, BooleanParquetValueConversion> {
public:
    static constexpr const common::PhysicalTypeID TYPE = common::PhysicalTypeID::BOOL;

public:
    BooleanColumnReader(ParquetReader& reader, common::LogicalType type,
        const lbug_parquet::format::SchemaElement& schema, uint64_t schemaIdx, uint64_t maxDefine,
        uint64_t maxRepeat)
        : TemplatedColumnReader<bool, BooleanParquetValueConversion>(reader, std::move(type),
              schema, schemaIdx, maxDefine, maxRepeat),
          bytePos(0){};

    uint8_t bytePos;

    void initializeRead(uint64_t rowGroupIdx,
        const std::vector<lbug_parquet::format::ColumnChunk>& columns,
        lbug_apache::thrift::protocol::TProtocol& protocol) override;

    inline void resetPage() override { bytePos = 0; }
};

struct BooleanParquetValueConversion {
    static bool dictRead(ByteBuffer& /*dict*/, uint32_t& /*offset*/, ColumnReader& /*reader*/) {
        throw common::CopyException{"Dicts for booleans make no sense"};
    }

    static bool plainRead(ByteBuffer& plainData, ColumnReader& reader);

    static inline void plainSkip(ByteBuffer& plainData, ColumnReader& reader) {
        plainRead(plainData, reader);
    }
};

} // namespace processor
} // namespace lbug
