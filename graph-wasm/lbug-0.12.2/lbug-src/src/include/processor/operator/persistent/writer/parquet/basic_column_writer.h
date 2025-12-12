#pragma once

#include "parquet_types.h"
#include "processor/operator/persistent/writer/parquet/column_writer.h"

namespace lbug {
namespace processor {

class BasicColumnWriterState : public ColumnWriterState {
public:
    BasicColumnWriterState(lbug_parquet::format::RowGroup& rowGroup, uint64_t colIdx)
        : rowGroup{rowGroup}, colIdx{colIdx} {
        pageInfo.emplace_back();
    }

    lbug_parquet::format::RowGroup& rowGroup;
    uint64_t colIdx;
    std::vector<PageInformation> pageInfo;
    std::vector<PageWriteInformation> writeInfo;
    std::unique_ptr<ColumnWriterStatistics> statsState;
    uint64_t currentPage = 0;
};

class BasicColumnWriter : public ColumnWriter {
public:
    BasicColumnWriter(ParquetWriter& writer, uint64_t schemaIdx,
        std::vector<std::string> schemaPath, uint64_t maxRepeat, uint64_t maxDefine,
        bool canHaveNulls)
        : ColumnWriter(writer, schemaIdx, std::move(schemaPath), maxRepeat, maxDefine,
              canHaveNulls) {}

public:
    std::unique_ptr<ColumnWriterState> initializeWriteState(
        lbug_parquet::format::RowGroup& rowGroup) override;
    void prepare(ColumnWriterState& state, ColumnWriterState* parent, common::ValueVector* vector,
        uint64_t count) override;
    void beginWrite(ColumnWriterState& state) override;
    void write(ColumnWriterState& state, common::ValueVector* vector, uint64_t count) override;
    void finalizeWrite(ColumnWriterState& state) override;

protected:
    void writeLevels(common::Serializer& bufferedSerializer, const std::vector<uint16_t>& levels,
        uint64_t maxValue, uint64_t startOffset, uint64_t count);

    virtual lbug_parquet::format::Encoding::type getEncoding(BasicColumnWriterState& /*state*/) {
        return lbug_parquet::format::Encoding::PLAIN;
    }

    void nextPage(BasicColumnWriterState& state);
    void flushPage(BasicColumnWriterState& state);

    // Initializes the state used to track statistics during writing. Only used for scalar types.
    virtual std::unique_ptr<ColumnWriterStatistics> initializeStatsState() {
        return std::make_unique<ColumnWriterStatistics>();
    }

    // Initialize the writer for a specific page. Only used for scalar types.
    virtual std::unique_ptr<ColumnWriterPageState> initializePageState(
        BasicColumnWriterState& /*state*/) {
        return nullptr;
    }

    // Flushes the writer for a specific page. Only used for scalar types.
    virtual void flushPageState(common::Serializer& /*bufferedSerializer*/,
        ColumnWriterPageState* /*state*/) {}

    // Retrieves the row size of a vector at the specified location. Only used for scalar types.
    virtual uint64_t getRowSize(common::ValueVector* /*vector*/, uint64_t /*index*/,
        BasicColumnWriterState& /*state*/) {
        KU_UNREACHABLE;
    }
    // Writes a (subset of a) vector to the specified serializer. Only used for scalar types.
    virtual void writeVector(common::Serializer& bufferedSerializer, ColumnWriterStatistics* stats,
        ColumnWriterPageState* pageState, common::ValueVector* vector, uint64_t chunkStart,
        uint64_t chunkEnd) = 0;

    virtual bool hasDictionary(BasicColumnWriterState& /*writerState*/) { return false; }
    // The number of elements in the dictionary.
    virtual uint64_t dictionarySize(BasicColumnWriterState& /*writerState*/) { KU_UNREACHABLE; }
    void writeDictionary(BasicColumnWriterState& state,
        std::unique_ptr<common::BufferWriter> bufferedSerializer, uint64_t rowCount);
    virtual void flushDictionary(BasicColumnWriterState& /*state*/,
        ColumnWriterStatistics* /*stats*/) {
        KU_UNREACHABLE;
    }

    void setParquetStatistics(BasicColumnWriterState& state,
        lbug_parquet::format::ColumnChunk& column);
    void registerToRowGroup(lbug_parquet::format::RowGroup& rowGroup);
};

} // namespace processor
} // namespace lbug
