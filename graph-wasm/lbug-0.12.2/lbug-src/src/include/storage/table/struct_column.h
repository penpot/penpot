#pragma once

#include "common/types/types.h"
#include "storage/table/column.h"

namespace lbug {
namespace storage {
class MemoryManager;

class StructColumn final : public Column {
public:
    StructColumn(std::string name, common::LogicalType dataType, FileHandle* dataFH,
        MemoryManager* mm, ShadowFile* shadowFile, bool enableCompression);

    static std::unique_ptr<ColumnChunkData> flushChunkData(const ColumnChunkData& chunk,
        PageAllocator& pageAllocator);

    Column* getChild(common::idx_t childIdx) const {
        KU_ASSERT(childIdx < childColumns.size());
        return childColumns[childIdx].get();
    }
    void writeSegment(ColumnChunkData& persistentChunk, SegmentState& state,
        common::offset_t offsetInSegment, const ColumnChunkData& data, common::offset_t dataOffset,
        common::length_t numValues) const override;

    std::vector<std::unique_ptr<ColumnChunkData>> checkpointSegment(
        ColumnCheckpointState&& checkpointState, PageAllocator& pageAllocator,
        bool canSplitSegment = true) const override;

protected:
    void scanSegment(const SegmentState& state, ColumnChunkData* resultChunk,
        common::offset_t startOffsetInSegment, common::row_idx_t numValuesToScan) const override;
    void scanSegment(const SegmentState& state, common::offset_t startOffsetInSegment,
        common::row_idx_t numValuesToScan, common::ValueVector* resultVector,
        common::offset_t offsetInResult) const override;

    void lookupInternal(const SegmentState& state, common::offset_t offsetInSegment,
        common::ValueVector* resultVector, uint32_t posInVector) const override;

    bool canCheckpointInPlace(const SegmentState& state,
        const ColumnCheckpointState& checkpointState) const override;

private:
    std::vector<std::unique_ptr<Column>> childColumns;
};

} // namespace storage
} // namespace lbug
