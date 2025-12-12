#include "storage/table/dictionary_column.h"

#include <algorithm>
#include <cstdint>

#include "common/types/ku_string.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/storage_utils.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/dictionary_chunk.h"
#include "storage/table/string_chunk_data.h"
#include "storage/table/string_column.h"
#include <bit>
#include <concepts>

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

using string_index_t = DictionaryChunk::string_index_t;
using string_offset_t = DictionaryChunk::string_offset_t;

DictionaryColumn::DictionaryColumn(const std::string& name, FileHandle* dataFH, MemoryManager* mm,
    ShadowFile* shadowFile, bool enableCompression) {
    auto dataColName = StorageUtils::getColumnName(name, StorageUtils::ColumnType::DATA, "");
    dataColumn = std::make_unique<Column>(dataColName, LogicalType::UINT8(), dataFH, mm, shadowFile,
        false /*enableCompression*/, false /*requireNullColumn*/);
    auto offsetColName = StorageUtils::getColumnName(name, StorageUtils::ColumnType::OFFSET, "");
    offsetColumn = std::make_unique<Column>(offsetColName, LogicalType::UINT64(), dataFH, mm,
        shadowFile, enableCompression, false /*requireNullColumn*/);
}

void DictionaryColumn::scan(const SegmentState& state, DictionaryChunk& dictChunk) const {
    auto offsetChunk = dictChunk.getOffsetChunk();
    auto stringDataChunk = dictChunk.getStringDataChunk();
    auto initialDictSize = offsetChunk->getNumValues();
    auto initialDictDataSize = stringDataChunk->getNumValues();

    auto& dataMetadata =
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::DATA).metadata;
    // Make sure that the chunk is large enough
    if (stringDataChunk->getNumValues() + dataMetadata.numValues > stringDataChunk->getCapacity()) {
        stringDataChunk->resize(
            std::bit_ceil(stringDataChunk->getNumValues() + dataMetadata.numValues));
    }
    dataColumn->scanSegment(StringColumn::getChildState(state, StringColumn::ChildStateIndex::DATA),
        stringDataChunk, 0,
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::DATA).metadata.numValues);

    auto& offsetMetadata =
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::OFFSET).metadata;
    // Make sure that the chunk is large enough
    if (offsetChunk->getNumValues() + offsetMetadata.numValues > offsetChunk->getCapacity()) {
        offsetChunk->resize(std::bit_ceil(offsetChunk->getNumValues() + offsetMetadata.numValues));
    }
    offsetColumn->scanSegment(
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::OFFSET), offsetChunk, 0,
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::OFFSET)
            .metadata.numValues);
    // Each offset needs to be incremented by the initial size of the dictionary data chunk
    for (row_idx_t i = initialDictSize; i < offsetChunk->getNumValues(); i++) {
        offsetChunk->setValue<string_offset_t>(
            offsetChunk->getValue<string_offset_t>(i) + initialDictDataSize, i);
    }
}

template<typename Result>
void DictionaryColumn::scan(const SegmentState& offsetState, const SegmentState& dataState,
    std::vector<std::pair<string_index_t, uint64_t>>& offsetsToScan, Result* result,
    const ColumnChunkMetadata& indexMeta) const {
    string_index_t firstOffsetToScan = 0, lastOffsetToScan = 0;
    auto comp = [](auto pair1, auto pair2) { return pair1.first < pair2.first; };
    auto duplicationFactor = (double)offsetState.metadata.numValues / indexMeta.numValues;
    if (duplicationFactor <= 0.5) {
        // If at least 50% of strings are duplicated, sort the offsets so we can re-use scanned
        // strings
        std::sort(offsetsToScan.begin(), offsetsToScan.end(), comp);
        firstOffsetToScan = offsetsToScan.front().first;
        lastOffsetToScan = offsetsToScan.back().first;
    } else {
        const auto& [min, max] =
            std::minmax_element(offsetsToScan.begin(), offsetsToScan.end(), comp);
        firstOffsetToScan = min->first;
        lastOffsetToScan = max->first;
    }
    // TODO(bmwinger): scan batches of adjacent values.
    // Ideally we scan values together until we reach empty pages
    // This would also let us use the same optimization for the data column,
    // where the worst case for the current method is much worse

    // Note that the list will contain duplicates when indices are duplicated.
    // Each distinct value is scanned once, and re-used when writing to each output value
    auto numOffsetsToScan = lastOffsetToScan - firstOffsetToScan + 1;
    // One extra offset to scan for the end offset of the last string
    std::vector<string_offset_t> offsets(numOffsetsToScan + 1);
    scanOffsets(offsetState, offsets.data(), firstOffsetToScan, numOffsetsToScan,
        dataState.metadata.numValues);

    if constexpr (std::same_as<Result, ColumnChunkData>) {
        auto& offsetChunk = *result->getDictionaryChunk()->getOffsetChunk();
        if (offsetChunk.getNumValues() + offsetsToScan.size() > offsetChunk.getCapacity()) {
            offsetChunk.resize(std::bit_ceil(offsetChunk.getNumValues() + offsetsToScan.size()));
        }
    }

    for (auto pos = 0u; pos < offsetsToScan.size(); pos++) {
        auto startOffset = offsets[offsetsToScan[pos].first - firstOffsetToScan];
        auto endOffset = offsets[offsetsToScan[pos].first - firstOffsetToScan + 1];
        auto lengthToScan = endOffset - startOffset;
        KU_ASSERT(endOffset >= startOffset);
        scanValue(dataState, startOffset, lengthToScan, result, offsetsToScan[pos].second);
        // For each string which has the same index in the dictionary as the one we scanned,
        // copy the scanned string to its position in the result vector
        if constexpr (std::same_as<Result, ValueVector>) {
            auto& scannedString = result->template getValue<ku_string_t>(offsetsToScan[pos].second);
            while (pos + 1 < offsetsToScan.size() &&
                   offsetsToScan[pos + 1].first == offsetsToScan[pos].first) {
                pos++;
                result->template setValue<ku_string_t>(offsetsToScan[pos].second, scannedString);
            }
        } else {
            // When scanning to chunks de-duplication should be done prior to this function such
            // that you can have multiple positions in the string index chunk pointing to one string
            // in this dictionary chunk.
            // The offset chunk cannot have multiple offsets pointing to the same data, even if
            // consecutive, since that would break the mechanism for calculating the size of a
            // string.
            KU_ASSERT(pos == offsetsToScan.size() - 1 ||
                      offsetsToScan[pos].first != offsetsToScan[pos + 1].first);
        }
    }
}

template void DictionaryColumn::scan<common::ValueVector>(const SegmentState& offsetState,
    const SegmentState& dataState,
    std::vector<std::pair<DictionaryChunk::string_index_t, uint64_t>>& offsetsToScan,
    common::ValueVector* result, const ColumnChunkMetadata& indexMeta) const;

template void DictionaryColumn::scan<StringChunkData>(const SegmentState& offsetState,
    const SegmentState& dataState,
    std::vector<std::pair<DictionaryChunk::string_index_t, uint64_t>>& offsetsToScan,
    StringChunkData* result, const ColumnChunkMetadata& indexMeta) const;

string_index_t DictionaryColumn::append(const DictionaryChunk& dictChunk, SegmentState& state,
    std::string_view val) const {
    const auto startOffset = dataColumn->appendValues(*dictChunk.getStringDataChunk(),
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::DATA),
        reinterpret_cast<const uint8_t*>(val.data()), nullptr /*nullChunkData*/, val.size());
    return offsetColumn->appendValues(*dictChunk.getOffsetChunk(),
        StringColumn::getChildState(state, StringColumn::ChildStateIndex::OFFSET),
        reinterpret_cast<const uint8_t*>(&startOffset), nullptr /*nullChunkData*/, 1 /*numValues*/);
}

void DictionaryColumn::scanOffsets(const SegmentState& state,
    DictionaryChunk::string_offset_t* offsets, uint64_t index, uint64_t numValues,
    uint64_t dataSize) const {
    // We either need to read the next value, or store the maximum string offset at the end.
    // Otherwise we won't know what the length of the last string is.
    if (index + numValues < state.metadata.numValues) {
        offsetColumn->scanSegment(state, index, numValues + 1, (uint8_t*)offsets);
    } else {
        offsetColumn->scanSegment(state, index, numValues, (uint8_t*)offsets);
        offsets[numValues] = dataSize;
    }
}

void DictionaryColumn::scanValue(const SegmentState& dataState, uint64_t startOffset,
    uint64_t length, ValueVector* resultVector, uint64_t offsetInVector) const {
    // Add string to vector first and read directly into the vector
    auto& kuString = StringVector::reserveString(resultVector, offsetInVector, length);
    dataColumn->scanSegment(dataState, startOffset, length, (uint8_t*)kuString.getData());
    // Update prefix to match the scanned string data
    if (!ku_string_t::isShortString(kuString.len)) {
        memcpy(kuString.prefix, kuString.getData(), ku_string_t::PREFIX_LENGTH);
    }
}

void DictionaryColumn::scanValue(const SegmentState& dataState, uint64_t startOffset,
    uint64_t length, StringChunkData* result, uint64_t offsetInResult) const {
    auto& stringDataChunk = *result->getDictionaryChunk().getStringDataChunk();
    auto& offsetChunk = *result->getDictionaryChunk().getOffsetChunk();
    auto& indexChunk = *result->getIndexColumnChunk();
    if (stringDataChunk.getCapacity() < stringDataChunk.getNumValues() + length) {
        stringDataChunk.resize(std::bit_ceil(stringDataChunk.getNumValues() + length));
    }
    if (offsetChunk.getNumValues() == offsetChunk.getCapacity()) {
        offsetChunk.resize(std::bit_ceil(offsetChunk.getNumValues() + 1));
    }
    if (offsetInResult >= indexChunk.getCapacity()) {
        indexChunk.resize(std::bit_ceil(offsetInResult + 1));
    }
    dataColumn->scanSegment(dataState, startOffset, length,
        stringDataChunk.getData<uint8_t>() + stringDataChunk.getNumValues());
    indexChunk.setValue<string_index_t>(offsetChunk.getNumValues(), offsetInResult);
    offsetChunk.setValue<string_offset_t>(stringDataChunk.getNumValues(),
        offsetChunk.getNumValues());
    stringDataChunk.setNumValues(stringDataChunk.getNumValues() + length);
}

bool DictionaryColumn::canCommitInPlace(const SegmentState& state, uint64_t numNewStrings,
    uint64_t totalStringLengthToAdd) const {
    if (!canDataCommitInPlace(
            StringColumn::getChildState(state, StringColumn::ChildStateIndex::DATA),
            totalStringLengthToAdd)) {
        return false;
    }
    if (!canOffsetCommitInPlace(
            StringColumn::getChildState(state, StringColumn::ChildStateIndex::OFFSET),
            StringColumn::getChildState(state, StringColumn::ChildStateIndex::DATA), numNewStrings,
            totalStringLengthToAdd)) {
        return false;
    }
    return true;
}

bool DictionaryColumn::canDataCommitInPlace(const SegmentState& dataState,
    uint64_t totalStringLengthToAdd) {
    // Make sure there is sufficient space in the data chunk (not currently compressed)
    auto totalStringDataAfterUpdate = dataState.metadata.numValues + totalStringLengthToAdd;
    if (totalStringDataAfterUpdate > dataState.metadata.getNumPages() * LBUG_PAGE_SIZE) {
        // Data cannot be updated in place
        return false;
    }
    return true;
}

bool DictionaryColumn::canOffsetCommitInPlace(const SegmentState& offsetState,
    const SegmentState& dataState, uint64_t numNewStrings, uint64_t totalStringLengthToAdd) const {
    auto totalStringOffsetsAfterUpdate = dataState.metadata.numValues + totalStringLengthToAdd;
    auto offsetCapacity =
        offsetState.metadata.compMeta.numValues(LBUG_PAGE_SIZE, offsetColumn->getDataType()) *
        offsetState.metadata.getNumPages();
    auto numStringsAfterUpdate = offsetState.metadata.numValues + numNewStrings;
    if (numStringsAfterUpdate > offsetCapacity) {
        // Offsets cannot be updated in place
        return false;
    }
    // Indices are limited to 32 bits but in theory could be larger than that since the offset
    // column can grow beyond the node group size.
    //
    // E.g. one big string is written first, followed by NODE_GROUP_SIZE-1 small strings,
    // which are all updated in-place many times (which may fit if the first string is large
    // enough that 2^n minus the first string's size is large enough to fit the other strings,
    // for some n.
    // 32 bits should give plenty of space for updates.
    if (numStringsAfterUpdate > std::numeric_limits<string_index_t>::max()) [[unlikely]] {
        return false;
    }
    if (offsetState.metadata.compMeta.canAlwaysUpdateInPlace()) {
        return true;
    }
    InPlaceUpdateLocalState localUpdateState{};
    if (!offsetState.metadata.compMeta.canUpdateInPlace(
            (const uint8_t*)&totalStringOffsetsAfterUpdate, 0 /*offset*/, 1 /*numValues*/,
            offsetColumn->getDataType().getPhysicalType(), localUpdateState)) {
        return false;
    }
    return true;
}

} // namespace storage
} // namespace lbug
