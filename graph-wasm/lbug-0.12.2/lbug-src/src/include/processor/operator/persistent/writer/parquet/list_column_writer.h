#pragma once

#include "processor/operator/persistent/writer/parquet/column_writer.h"

namespace lbug {
namespace processor {

class ListColumnWriter : public ColumnWriter {
public:
    ListColumnWriter(ParquetWriter& writer, uint64_t schemaIdx, std::vector<std::string> schema,
        uint64_t maxRepeat, uint64_t maxDefine, std::unique_ptr<ColumnWriter> childWriter,
        bool canHaveNulls)
        : ColumnWriter(writer, schemaIdx, std::move(schema), maxRepeat, maxDefine, canHaveNulls),
          childWriter(std::move(childWriter)) {}

    std::unique_ptr<ColumnWriterState> initializeWriteState(
        lbug_parquet::format::RowGroup& rowGroup) override;
    bool hasAnalyze() override;
    void analyze(ColumnWriterState& writerState, ColumnWriterState* parent,
        common::ValueVector* vector, uint64_t count) override;
    void finalizeAnalyze(ColumnWriterState& writerState) override;
    void prepare(ColumnWriterState& writerState, ColumnWriterState* parent,
        common::ValueVector* vector, uint64_t count) override;
    void beginWrite(ColumnWriterState& state) override;
    void write(ColumnWriterState& writerState, common::ValueVector* vector,
        uint64_t count) override;
    void finalizeWrite(ColumnWriterState& writerState) override;

private:
    std::unique_ptr<ColumnWriter> childWriter;
};

class ListColumnWriterState : public ColumnWriterState {
public:
    ListColumnWriterState(lbug_parquet::format::RowGroup& rowGroup, uint64_t colIdx)
        : rowGroup{rowGroup}, colIdx{colIdx} {}

    lbug_parquet::format::RowGroup& rowGroup;
    uint64_t colIdx;
    std::unique_ptr<ColumnWriterState> childState;
    uint64_t parentIdx = 0;
};

} // namespace processor
} // namespace lbug
