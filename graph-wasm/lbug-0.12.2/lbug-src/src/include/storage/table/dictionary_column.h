#pragma once

#include "dictionary_chunk.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/string_chunk_data.h"

namespace lbug {
namespace storage {

class DictionaryColumn {
public:
    DictionaryColumn(const std::string& name, FileHandle* dataFH, MemoryManager* mm,
        ShadowFile* shadowFile, bool enableCompression);

    void scan(const SegmentState& state, DictionaryChunk& dictChunk) const;
    // Offsets to scan should be a sorted list of pairs mapping the index of the entry in the string
    // dictionary (as read from the index column) to the output index in the result vector to store
    // the string.
    template<class Result>
    void scan(const SegmentState& offsetState, const SegmentState& dataState,
        std::vector<std::pair<DictionaryChunk::string_index_t, uint64_t>>& offsetsToScan,
        Result* result, const ColumnChunkMetadata& indexMeta) const;

    DictionaryChunk::string_index_t append(const DictionaryChunk& dictChunk, SegmentState& state,
        std::string_view val) const;

    bool canCommitInPlace(const SegmentState& state, uint64_t numNewStrings,
        uint64_t totalStringLengthToAdd) const;

    Column* getDataColumn() const { return dataColumn.get(); }
    Column* getOffsetColumn() const { return offsetColumn.get(); }

private:
    void scanOffsets(const SegmentState& state, DictionaryChunk::string_offset_t* offsets,
        uint64_t index, uint64_t numValues, uint64_t dataSize) const;
    void scanValue(const SegmentState& dataState, uint64_t startOffset, uint64_t endOffset,
        StringChunkData* result, uint64_t offsetInVector) const;
    void scanValue(const SegmentState& dataState, uint64_t startOffset, uint64_t endOffset,
        common::ValueVector* resultVector, uint64_t offsetInVector) const;

    static bool canDataCommitInPlace(const SegmentState& dataState,
        uint64_t totalStringLengthToAdd);
    bool canOffsetCommitInPlace(const SegmentState& offsetState, const SegmentState& dataState,
        uint64_t numNewStrings, uint64_t totalStringLengthToAdd) const;

private:
    // The offset column stores the offsets for each index, and the data column stores the data in
    // order. Values are never removed from the dictionary during in-place updates, only appended to
    // the end.
    std::unique_ptr<Column> dataColumn;
    std::unique_ptr<Column> offsetColumn;
};

} // namespace storage
} // namespace lbug
