#pragma once

#include "common/types/types.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/table/dictionary_column.h"

namespace lbug {
namespace storage {

class StringColumn final : public Column {
public:
    enum class ChildStateIndex : common::idx_t { DATA = 0, OFFSET = 1, INDEX = 2 };
    static constexpr size_t CHILD_STATE_COUNT = 3;

    StringColumn(std::string name, common::LogicalType dataType, FileHandle* dataFH,
        MemoryManager* mm, ShadowFile* shadowFile, bool enableCompression);

    static std::unique_ptr<ColumnChunkData> flushChunkData(const ColumnChunkData& chunkData,
        PageAllocator& pageAllocator);

    void writeSegment(ColumnChunkData& persistentChunk, SegmentState& state,
        common::offset_t dstOffsetInSegment, const ColumnChunkData& data,
        common::offset_t srcOffset, common::length_t numValues) const override;

    std::vector<std::unique_ptr<ColumnChunkData>> checkpointSegment(
        ColumnCheckpointState&& checkpointState, PageAllocator& pageAllocator,
        bool canSplitSegment = true) const override;

    const DictionaryColumn& getDictionary() const { return dictionary; }
    const Column* getIndexColumn() const { return indexColumn.get(); }

    static SegmentState& getChildState(SegmentState& state, ChildStateIndex child);
    static const SegmentState& getChildState(const SegmentState& state, ChildStateIndex child);

protected:
    void scanSegment(const SegmentState& state, common::offset_t startOffsetInChunk,
        common::row_idx_t numValuesToScan, common::ValueVector* resultVector,
        common::offset_t offsetInResult) const override;

    void scanSegment(const SegmentState& state, ColumnChunkData* resultChunk,
        common::offset_t startOffsetInSegment, common::row_idx_t numValuesToScan) const override;

    void scanUnfiltered(const SegmentState& state, common::offset_t startOffsetInChunk,
        common::offset_t numValuesToRead, common::ValueVector* resultVector,
        common::sel_t startPosInVector = 0) const;
    void scanFiltered(const SegmentState& state, common::offset_t startOffsetInChunk,
        common::ValueVector* resultVector, common::sel_t startPosInVector) const;

    void lookupInternal(const SegmentState& state, common::offset_t nodeOffset,
        common::ValueVector* resultVector, uint32_t posInVector) const override;

private:
    bool canCheckpointInPlace(const SegmentState& state,
        const ColumnCheckpointState& checkpointState) const override;

    bool canIndexCommitInPlace(const SegmentState& state, uint64_t numStrings,
        common::offset_t maxOffset) const;

private:
    // Main column stores indices of values in the dictionary
    DictionaryColumn dictionary;

    std::unique_ptr<Column> indexColumn;
};

} // namespace storage
} // namespace lbug
