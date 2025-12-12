#pragma once

#include "parquet_rle_bp_encoder.h"
#include "parquet_types.h"
#include "processor/operator/persistent/writer/parquet/basic_column_writer.h"

namespace lbug {
namespace processor {

struct StringHash {
    std::size_t operator()(const common::ku_string_t& k) const;
};

struct StringEquality {
    bool operator()(const common::ku_string_t& a, const common::ku_string_t& b) const;
};

template<typename T>
using string_map_t = std::unordered_map<common::ku_string_t, T, StringHash, StringEquality>;

class StringStatisticsState : public ColumnWriterStatistics {
public:
    bool hasStats = false;
    bool valuesTooBig = false;
    std::string min;
    std::string max;

public:
    bool hasValidStats() const { return hasStats; }

    void update(const common::ku_string_t& val);

    std::string getMin() override { return getMinValue(); }
    std::string getMax() override { return getMaxValue(); }
    std::string getMinValue() override { return hasValidStats() ? min : std::string(); }
    std::string getMaxValue() override { return hasValidStats() ? max : std::string(); }
};

class StringColumnWriterState : public BasicColumnWriterState {
public:
    StringColumnWriterState(lbug_parquet::format::RowGroup& rowGroup, uint64_t colIdx,
        storage::MemoryManager* mm)
        : BasicColumnWriterState{rowGroup, colIdx}, estimatedDictPageSize{0},
          estimatedRlePagesSize{0}, estimatedPlainSize{0},
          overflowBuffer{std::make_unique<common::InMemOverflowBuffer>(mm)}, keyBitWidth{0} {}

    // Analysis state.
    uint64_t estimatedDictPageSize;
    uint64_t estimatedRlePagesSize;
    uint64_t estimatedPlainSize;

    // Dictionary and accompanying string heap.
    string_map_t<uint32_t> dictionary;
    std::unique_ptr<common::InMemOverflowBuffer> overflowBuffer;
    // key_bit_width== 0 signifies the chunk is written in plain encoding
    uint32_t keyBitWidth;

    bool isDictionaryEncoded() const { return keyBitWidth != 0; }
};

class StringWriterPageState : public ColumnWriterPageState {
public:
    explicit StringWriterPageState(uint32_t bitWidth, const string_map_t<uint32_t>& values)
        : bitWidth(bitWidth), dictionary(values), encoder(bitWidth), writtenValue(false) {
        KU_ASSERT(isDictionaryEncoded() || (bitWidth == 0 && dictionary.empty()));
    }

    inline bool isDictionaryEncoded() const { return bitWidth != 0; }
    // If 0, we're writing a plain page.
    uint32_t bitWidth;
    const string_map_t<uint32_t>& dictionary;
    RleBpEncoder encoder;
    bool writtenValue;
};

class StringColumnWriter : public BasicColumnWriter {
public:
    StringColumnWriter(ParquetWriter& writer, uint64_t schemaIdx,
        std::vector<std::string> schemaPath, uint64_t maxRepeat, uint64_t maxDefine,
        bool canHaveNulls, storage::MemoryManager* mm)
        : BasicColumnWriter(writer, schemaIdx, std::move(schemaPath), maxRepeat, maxDefine,
              canHaveNulls),
          mm{mm} {}

public:
    inline std::unique_ptr<ColumnWriterStatistics> initializeStatsState() override {
        return std::make_unique<StringStatisticsState>();
    }

    std::unique_ptr<ColumnWriterState> initializeWriteState(
        lbug_parquet::format::RowGroup& rowGroup) override;

    inline bool hasAnalyze() override { return true; }

    inline std::unique_ptr<ColumnWriterPageState> initializePageState(
        BasicColumnWriterState& state_p) override {
        auto& state = reinterpret_cast<StringColumnWriterState&>(state_p);
        return std::make_unique<StringWriterPageState>(state.keyBitWidth, state.dictionary);
    }

    inline lbug_parquet::format::Encoding::type getEncoding(
        BasicColumnWriterState& writerState) override {
        auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);
        return state.isDictionaryEncoded() ? lbug_parquet::format::Encoding::RLE_DICTIONARY :
                                             lbug_parquet::format::Encoding::PLAIN;
    }

    inline bool hasDictionary(BasicColumnWriterState& writerState) override {
        auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);
        return state.isDictionaryEncoded();
    }

    inline uint64_t dictionarySize(BasicColumnWriterState& writerState) override {
        auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);
        KU_ASSERT(state.isDictionaryEncoded());
        return state.dictionary.size();
    }

    void analyze(ColumnWriterState& writerState, ColumnWriterState* parent,
        common::ValueVector* vector, uint64_t count) override;

    void finalizeAnalyze(ColumnWriterState& writerState) override;

    void writeVector(common::Serializer& bufferedSerializer, ColumnWriterStatistics* statsToWrite,
        ColumnWriterPageState* writerPageState, common::ValueVector* vector, uint64_t chunkStart,
        uint64_t chunkEnd) override;

    void flushPageState(common::Serializer& bufferedSerializer,
        ColumnWriterPageState* writerPageState) override;

    void flushDictionary(BasicColumnWriterState& writerState,
        ColumnWriterStatistics* writerStats) override;

    uint64_t getRowSize(common::ValueVector* vector, uint64_t index,
        BasicColumnWriterState& writerState) override;

private:
    storage::MemoryManager* mm;
};

} // namespace processor
} // namespace lbug
