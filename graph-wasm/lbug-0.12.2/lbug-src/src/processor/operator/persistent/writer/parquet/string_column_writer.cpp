#include "processor/operator/persistent/writer/parquet/string_column_writer.h"

#include "common/constants.h"
#include "function/comparison/comparison_functions.h"
#include "function/hash/hash_functions.h"
#include "processor/operator/persistent/reader/parquet/parquet_rle_bp_decoder.h"

namespace lbug {
namespace processor {

using namespace lbug::common;
using namespace lbug_parquet::format;

std::size_t StringHash::operator()(const ku_string_t& k) const {
    hash_t result = 0;
    function::Hash::operation(k, result);
    return result;
}

bool StringEquality::operator()(const ku_string_t& a, const ku_string_t& b) const {
    uint8_t result = 0;
    function::Equals::operation(a, b, result, nullptr /* leftVector */, nullptr /* rightVector */);
    return result;
}

void StringStatisticsState::update(const ku_string_t& val) {
    if (valuesTooBig) {
        return;
    }
    if (val.len > ParquetConstants::MAX_STRING_STATISTICS_SIZE) {
        // we avoid gathering stats when individual string values are too large
        // this is because the statistics are copied into the Parquet file meta data in
        // uncompressed format ideally we avoid placing several mega or giga-byte long strings
        // there we put a threshold of 10KB, if we see strings that exceed this threshold we
        // avoid gathering stats
        valuesTooBig = true;
        min = std::string();
        max = std::string();
        return;
    }
    if (!hasStats || val.getAsString() < min) {
        min = val.getAsString();
    }
    if (!hasStats || val.getAsString() > max) {
        max = val.getAsString();
    }
    hasStats = true;
}

std::unique_ptr<ColumnWriterState> StringColumnWriter::initializeWriteState(RowGroup& rowGroup) {
    auto result = std::make_unique<StringColumnWriterState>(rowGroup, rowGroup.columns.size(), mm);
    registerToRowGroup(rowGroup);
    return result;
}

void StringColumnWriter::analyze(ColumnWriterState& writerState, ColumnWriterState* parent,
    ValueVector* vector, uint64_t count) {
    auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);
    uint64_t vcount =
        parent ? parent->definitionLevels.size() - state.definitionLevels.size() : count;
    uint64_t parentIdx = state.definitionLevels.size();
    uint64_t vectorIdx = 0;
    uint32_t newValueIdx = state.dictionary.size();
    uint32_t lastValueIdx = -1;
    uint64_t runLen = 0;
    uint64_t runCount = 0;
    for (auto i = 0u; i < vcount; i++) {
        if (parent && !parent->isEmpty.empty() && parent->isEmpty[parentIdx + i]) {
            continue;
        }
        auto pos = getVectorPos(vector, vectorIdx);
        if (!vector->isNull(pos)) {
            runLen++;
            const auto& value = vector->getValue<ku_string_t>(pos);
            // Try to insert into the dictionary. If it's already there, we get back the value
            // index.
            ku_string_t valueToInsert;
            StringVector::copyToRowData(vector, pos, reinterpret_cast<uint8_t*>(&valueToInsert),
                state.overflowBuffer.get());
            auto found = state.dictionary.insert(
                string_map_t<uint32_t>::value_type(valueToInsert, newValueIdx));
            state.estimatedPlainSize += value.len + ParquetConstants::STRING_LENGTH_SIZE;
            if (found.second) {
                // String didn't exist yet in the dictionary.
                newValueIdx++;
                state.estimatedDictPageSize +=
                    value.len + ParquetConstants::MAX_DICTIONARY_KEY_SIZE;
            }
            // If the value changed, we will encode it in the page.
            if (lastValueIdx != found.first->second) {
                // we will add the value index size later, when we know the total number of keys
                state.estimatedRlePagesSize += RleBpEncoder::getVarintSize(runLen);
                runLen = 0;
                runCount++;
                lastValueIdx = found.first->second;
            }
        }
        vectorIdx++;
    }
    // Add the costs of keys sizes. We don't know yet how many bytes the keys need as we haven't
    // seen all the values. therefore we use an over-estimation of
    state.estimatedRlePagesSize += ParquetConstants::MAX_DICTIONARY_KEY_SIZE * runCount;
}

void StringColumnWriter::finalizeAnalyze(ColumnWriterState& writerState) {
    auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);

    // Check if a dictionary will require more space than a plain write, or if the dictionary
    // page is going to be too large.
    if (state.estimatedDictPageSize > ParquetConstants::MAX_UNCOMPRESSED_DICT_PAGE_SIZE ||
        state.estimatedRlePagesSize + state.estimatedDictPageSize > state.estimatedPlainSize) {
        // Clearing the dictionary signals a plain write.
        state.dictionary.clear();
        state.keyBitWidth = 0;
    } else {
        state.keyBitWidth = RleBpDecoder::ComputeBitWidth(state.dictionary.size());
    }
}

void StringColumnWriter::writeVector(common::Serializer& serializer,
    ColumnWriterStatistics* statsToWrite, ColumnWriterPageState* writerPageState,
    ValueVector* vector, uint64_t chunkStart, uint64_t chunkEnd) {
    auto pageState = reinterpret_cast<StringWriterPageState*>(writerPageState);
    auto stats = reinterpret_cast<StringStatisticsState*>(statsToWrite);

    if (pageState->isDictionaryEncoded()) {
        // Dictionary based page.
        for (auto r = chunkStart; r < chunkEnd; r++) {
            auto pos = getVectorPos(vector, r);
            if (vector->isNull(pos)) {
                continue;
            }
            auto value_index = pageState->dictionary.at(vector->getValue<ku_string_t>(pos));
            if (!pageState->writtenValue) {
                // Write the bit-width as a one-byte entry.
                serializer.write<uint8_t>(pageState->bitWidth);
                // Now begin writing the actual value.
                pageState->encoder.beginWrite(value_index);
                pageState->writtenValue = true;
            } else {
                pageState->encoder.writeValue(serializer, value_index);
            }
        }
    } else {
        for (auto r = chunkStart; r < chunkEnd; r++) {
            auto pos = getVectorPos(vector, r);
            if (vector->isNull(pos)) {
                continue;
            }
            auto& str = vector->getValue<ku_string_t>(pos);
            stats->update(str);
            serializer.write<uint32_t>(str.len);
            serializer.write(str.getData(), str.len);
        }
    }
}

void StringColumnWriter::flushPageState(common::Serializer& serializer,
    ColumnWriterPageState* writerPageState) {
    auto pageState = reinterpret_cast<StringWriterPageState*>(writerPageState);
    if (pageState->bitWidth != 0) {
        if (!pageState->writtenValue) {
            // all values are null
            // just write the bit width
            serializer.write<uint8_t>(pageState->bitWidth);
            return;
        }
        pageState->encoder.finishWrite(serializer);
    }
}

void StringColumnWriter::flushDictionary(BasicColumnWriterState& writerState,
    ColumnWriterStatistics* writerStats) {
    auto stats = reinterpret_cast<StringStatisticsState*>(writerStats);
    auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);
    if (!state.isDictionaryEncoded()) {
        return;
    }
    // First we need to sort the values in index order.
    auto values = std::vector<ku_string_t>(state.dictionary.size());
    for (const auto& entry : state.dictionary) {
        KU_ASSERT(values[entry.second].len == 0);
        values[entry.second] = entry.first;
    }
    // First write the contents of the dictionary page to a temporary buffer.
    auto bufferedSerializer = std::make_unique<common::BufferWriter>();
    for (auto r = 0u; r < values.size(); r++) {
        auto& value = values[r];
        // Update the statistics.
        stats->update(value);
        // Write this string value to the dictionary.
        bufferedSerializer->write<uint32_t>(value.len);
        bufferedSerializer->write(value.getData(), value.len);
    }
    // Flush the dictionary page and add it to the to-be-written pages.
    writeDictionary(state, std::move(bufferedSerializer), values.size());
}

uint64_t StringColumnWriter::getRowSize(ValueVector* vector, uint64_t index,
    BasicColumnWriterState& writerState) {
    auto& state = reinterpret_cast<StringColumnWriterState&>(writerState);
    if (state.isDictionaryEncoded()) {
        return (state.keyBitWidth + 7) / 8;
    } else {
        return vector->getValue<ku_string_t>(getVectorPos(vector, index)).len;
    }
}

} // namespace processor
} // namespace lbug
