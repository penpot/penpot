#pragma once

#include "basic_column_writer.h"
#include "common/serializer/serializer.h"
#include "function/cast/functions/numeric_limits.h"
#include "function/comparison/comparison_functions.h"

namespace lbug {
namespace processor {

template<class SRC, class T, class OP>
class NumericStatisticsState : public ColumnWriterStatistics {
public:
    NumericStatisticsState()
        : min(function::NumericLimits<T>::maximum()), max(function::NumericLimits<T>::minimum()) {}

    T min;
    T max;

public:
    bool hasStats() { return min <= max; }

    std::string getMin() override {
        return function::NumericLimits<SRC>::isSigned() ? getMinValue() : std::string();
    }
    std::string getMax() override {
        return function::NumericLimits<SRC>::isSigned() ? getMaxValue() : std::string();
    }
    std::string getMinValue() override {
        return hasStats() ? std::string((char*)&min, sizeof(T)) : std::string();
    }
    std::string getMaxValue() override {
        return hasStats() ? std::string((char*)&max, sizeof(T)) : std::string();
    }
};

struct BaseParquetOperator {
    template<class SRC, class TGT>
    inline static std::unique_ptr<ColumnWriterStatistics> initializeStats() {
        return std::make_unique<NumericStatisticsState<SRC, TGT, BaseParquetOperator>>();
    }

    template<class SRC, class TGT>
    static void handleStats(ColumnWriterStatistics* stats, SRC /*sourceValue*/, TGT targetValue) {
        auto& numericStats = (NumericStatisticsState<SRC, TGT, BaseParquetOperator>&)*stats;
        uint8_t result = 0;
        function::LessThan::operation(targetValue, numericStats.min, result,
            nullptr /* leftVector */, nullptr /* rightVector */);
        if (result != 0) {
            numericStats.min = targetValue;
        }
        function::GreaterThan::operation(targetValue, numericStats.max, result,
            nullptr /* leftVector */, nullptr /* rightVector */);
        if (result != 0) {
            numericStats.max = targetValue;
        }
    }
};

struct ParquetCastOperator : public BaseParquetOperator {
    template<class SRC, class TGT>
    static TGT Operation(SRC input) {
        return TGT(input);
    }
};

template<class SRC, class TGT, class OP = ParquetCastOperator>
class StandardColumnWriter : public BasicColumnWriter {
public:
    StandardColumnWriter(ParquetWriter& writer, uint64_t schemaIdx,
        std::vector<std::string> schemaPath, uint64_t maxRepeat, uint64_t maxDefine,
        bool canHaveNulls)
        : BasicColumnWriter(writer, schemaIdx, std::move(schemaPath), maxRepeat, maxDefine,
              canHaveNulls) {}

    std::unique_ptr<ColumnWriterStatistics> initializeStatsState() override {
        return OP::template initializeStats<SRC, TGT>();
    }

    void templatedWritePlain(common::ValueVector* vector, ColumnWriterStatistics* stats,
        uint64_t chunkStart, uint64_t chunkEnd, common::Serializer& ser) {
        for (auto r = chunkStart; r < chunkEnd; r++) {
            auto pos = getVectorPos(vector, r);
            if (!vector->isNull(pos)) {
                TGT targetValue = OP::template Operation<SRC, TGT>(vector->getValue<SRC>(pos));
                OP::template handleStats<SRC, TGT>(stats, vector->getValue<SRC>(pos), targetValue);
                ser.write<TGT>(targetValue);
            }
        }
    }

    void writeVector(common::Serializer& bufferedSerializer, ColumnWriterStatistics* stats,
        ColumnWriterPageState* /*pageState*/, common::ValueVector* vector, uint64_t chunkStart,
        uint64_t chunkEnd) override {
        templatedWritePlain(vector, stats, chunkStart, chunkEnd, bufferedSerializer);
    }

    uint64_t getRowSize(common::ValueVector* /*vector*/, uint64_t /*index*/,
        BasicColumnWriterState& /*state*/) override {
        return sizeof(TGT);
    }
};

} // namespace processor
} // namespace lbug
