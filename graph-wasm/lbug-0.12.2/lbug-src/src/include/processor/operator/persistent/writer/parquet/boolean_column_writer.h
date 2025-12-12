#pragma once

#include "processor/operator/persistent/writer/parquet/basic_column_writer.h"

namespace lbug {
namespace processor {

class BooleanStatisticsState : public ColumnWriterStatistics {
public:
    BooleanStatisticsState() : min{true}, max{false} {}

    bool min;
    bool max;

public:
    bool hasStats() const { return !(min && !max); }

    std::string getMin() override { return getMinValue(); }
    std::string getMax() override { return getMaxValue(); }
    std::string getMinValue() override {
        return hasStats() ? std::string(reinterpret_cast<const char*>(&min), sizeof(bool)) :
                            std::string();
    }
    std::string getMaxValue() override {
        return hasStats() ? std::string(reinterpret_cast<const char*>(&max), sizeof(bool)) :
                            std::string();
    }
};

class BooleanWriterPageState : public ColumnWriterPageState {
public:
    uint8_t byte = 0;
    uint8_t bytePos = 0;
};

class BooleanColumnWriter : public BasicColumnWriter {
public:
    BooleanColumnWriter(ParquetWriter& writer, uint64_t schemaIdx,
        std::vector<std::string> schemaPath, uint64_t maxRepeat, uint64_t maxDefine,
        bool canHaveNulls)
        : BasicColumnWriter(writer, schemaIdx, std::move(schemaPath), maxRepeat, maxDefine,
              canHaveNulls) {}

    inline std::unique_ptr<ColumnWriterStatistics> initializeStatsState() override {
        return std::make_unique<BooleanStatisticsState>();
    }

    inline uint64_t getRowSize(common::ValueVector* /*vector*/, uint64_t /*index*/,
        BasicColumnWriterState& /*state*/) override {
        return sizeof(bool);
    }

    inline std::unique_ptr<ColumnWriterPageState> initializePageState(
        BasicColumnWriterState& /*state*/) override {
        return std::make_unique<BooleanWriterPageState>();
    }

    void writeVector(common::Serializer& bufferedSerializer,
        ColumnWriterStatistics* writerStatistics, ColumnWriterPageState* writerPageState,
        common::ValueVector* vector, uint64_t chunkStart, uint64_t chunkEnd) override;

    void flushPageState(common::Serializer& temp_writer,
        ColumnWriterPageState* writerPageState) override;
};

} // namespace processor
} // namespace lbug
