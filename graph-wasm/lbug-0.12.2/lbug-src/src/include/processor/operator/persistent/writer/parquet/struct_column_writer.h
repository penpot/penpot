#pragma once

#include "processor/operator/persistent/writer/parquet/column_writer.h"

namespace lbug {
namespace processor {

class StructColumnWriter : public ColumnWriter {
public:
    StructColumnWriter(ParquetWriter& writer, uint64_t schemaIdx, std::vector<std::string> schema,
        uint64_t maxRepeat, uint64_t maxDefine,
        std::vector<std::unique_ptr<ColumnWriter>> childWriter, bool canHaveNull)
        : ColumnWriter{writer, schemaIdx, std::move(schema), maxRepeat, maxDefine, canHaveNull},
          childWriters{std::move(childWriter)} {}

    std::vector<std::unique_ptr<ColumnWriter>> childWriters;

public:
    std::unique_ptr<ColumnWriterState> initializeWriteState(
        lbug_parquet::format::RowGroup& rowGroup) override;
    bool hasAnalyze() override;
    void analyze(ColumnWriterState& state, ColumnWriterState* parent, common::ValueVector* vector,
        uint64_t count) override;
    void finalizeAnalyze(ColumnWriterState& state) override;
    void prepare(ColumnWriterState& state, ColumnWriterState* parent, common::ValueVector* vector,
        uint64_t count) override;

    void beginWrite(ColumnWriterState& state) override;
    void write(ColumnWriterState& state, common::ValueVector* vector, uint64_t count) override;
    void finalizeWrite(ColumnWriterState& state) override;
};

class StructColumnWriterState : public ColumnWriterState {
public:
    StructColumnWriterState(lbug_parquet::format::RowGroup& rowGroup, uint64_t colIdx)
        : rowGroup(rowGroup), colIdx(colIdx) {}

    lbug_parquet::format::RowGroup& rowGroup;
    uint64_t colIdx;
    std::vector<std::unique_ptr<ColumnWriterState>> childStates;
};

} // namespace processor
} // namespace lbug
