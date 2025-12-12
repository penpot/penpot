#pragma once

#include "column_reader.h"

namespace lbug {
namespace processor {

class StructColumnReader : public ColumnReader {
public:
    static constexpr const common::PhysicalTypeID TYPE = common::PhysicalTypeID::STRUCT;

public:
    StructColumnReader(ParquetReader& reader, common::LogicalType type,
        const lbug_parquet::format::SchemaElement& schema, uint64_t schemaIdx, uint64_t maxDefine,
        uint64_t maxRepeat, std::vector<std::unique_ptr<ColumnReader>> childReaders);

    void initializeRead(uint64_t rowGroupIdx,
        const std::vector<lbug_parquet::format::ColumnChunk>& columns,
        lbug_apache::thrift::protocol::TProtocol& protocol) override;
    uint64_t read(uint64_t num_values, parquet_filter_t& filter, uint8_t* define_out,
        uint8_t* repeat_out, common::ValueVector* result) override;
    ColumnReader* getChildReader(uint64_t childIdx);

private:
    uint64_t getTotalCompressedSize() override;
    void registerPrefetch(ThriftFileTransport& transport, bool allow_merge) override;
    void skip(uint64_t num_values) override;
    uint64_t getGroupRowsAvailable() override;

private:
    std::vector<std::unique_ptr<ColumnReader>> childReaders;
};

} // namespace processor
} // namespace lbug
