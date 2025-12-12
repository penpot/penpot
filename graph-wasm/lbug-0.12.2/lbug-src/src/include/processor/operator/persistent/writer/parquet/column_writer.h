#pragma once

#include "common/serializer/buffer_writer.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "parquet_types.h"

namespace lbug {
namespace processor {
class ParquetWriter;

struct PageInformation {
    uint64_t offset = 0;
    uint64_t rowCount = 0;
    uint64_t emptyCount = 0;
    uint64_t estimatedPageSize = 0;
};

class ColumnWriterPageState {
public:
    virtual ~ColumnWriterPageState() = default;
};

struct PageWriteInformation {
    lbug_parquet::format::PageHeader pageHeader;
    std::shared_ptr<common::BufferWriter> bufferWriter;
    std::unique_ptr<common::Serializer> writer;
    std::unique_ptr<ColumnWriterPageState> pageState;
    uint64_t writePageIdx = 0;
    uint64_t writeCount = 0;
    uint64_t maxWriteCount = 0;
    size_t compressedSize = 0;
    uint8_t* compressedData = nullptr;
    std::unique_ptr<uint8_t[]> compressedBuf;
};

class ColumnWriterState {
public:
    virtual ~ColumnWriterState() = default;

    std::vector<uint16_t> definitionLevels;
    std::vector<uint16_t> repetitionLevels;
    std::vector<bool> isEmpty;
};

class ColumnWriterStatistics {
public:
    virtual ~ColumnWriterStatistics() = default;

    virtual std::string getMin() { return {}; }
    virtual std::string getMax() { return {}; }
    virtual std::string getMinValue() { return {}; }
    virtual std::string getMaxValue() { return {}; }
};

class ColumnWriter {
public:
    ColumnWriter(ParquetWriter& writer, uint64_t schemaIdx, std::vector<std::string> schemaPath,
        uint64_t maxRepeat, uint64_t maxDefine, bool canHaveNulls);
    virtual ~ColumnWriter() = default;

    // Create the column writer for a specific type recursively.
    // TODO(Ziyi): We currently don't have statistics to indicate whether a column
    // has null value or not. So canHaveNullsToCreate is always true.
    static std::unique_ptr<ColumnWriter> createWriterRecursive(
        std::vector<lbug_parquet::format::SchemaElement>& schemas, ParquetWriter& writer,
        const common::LogicalType& type, const std::string& name,
        std::vector<std::string> schemaPathToCreate, storage::MemoryManager* mm,
        uint64_t maxRepeatToCreate = 0, uint64_t maxDefineToCreate = 1,
        bool canHaveNullsToCreate = true);

    virtual std::unique_ptr<ColumnWriterState> initializeWriteState(
        lbug_parquet::format::RowGroup& rowGroup) = 0;
    // Indicates whether the write need to analyse the data before preparing it.
    virtual bool hasAnalyze() { return false; }
    virtual void analyze(ColumnWriterState& /*state*/, ColumnWriterState* /*parent*/,
        common::ValueVector* /*vector*/, uint64_t /*count*/) {
        KU_UNREACHABLE;
    }
    // Called after all data has been passed to Analyze.
    virtual void finalizeAnalyze(ColumnWriterState& /*state*/) { KU_UNREACHABLE; }
    virtual void prepare(ColumnWriterState& state, ColumnWriterState* parent,
        common::ValueVector* vector, uint64_t count) = 0;
    virtual void beginWrite(ColumnWriterState& state) = 0;
    virtual void write(ColumnWriterState& state, common::ValueVector* vector, uint64_t count) = 0;
    virtual void finalizeWrite(ColumnWriterState& state) = 0;
    inline uint64_t getVectorPos(common::ValueVector* vector, uint64_t idx) {
        return (vector->state == nullptr || !vector->state->isFlat()) ? idx : 0;
    }

    ParquetWriter& writer;
    uint64_t schemaIdx;
    std::vector<std::string> schemaPath;
    uint64_t maxRepeat;
    uint64_t maxDefine;
    bool canHaveNulls;
    // collected stats
    uint64_t nullCount;

protected:
    void handleDefineLevels(ColumnWriterState& state, ColumnWriterState* parent,
        common::ValueVector* vector, uint64_t count, uint16_t defineValue, uint16_t nullValue);
    void handleRepeatLevels(ColumnWriterState& stateToHandle, ColumnWriterState* parent);
    void compressPage(common::BufferWriter& bufferedSerializer, size_t& compressedSize,
        uint8_t*& compressedData, std::unique_ptr<uint8_t[]>& compressedBuf);
};

} // namespace processor
} // namespace lbug
