#pragma once

#include "common/null_mask.h"
#include "common/types/types.h"
#include "storage/table/column_reader_writer.h"

namespace lbug {
namespace storage {
class MemoryManager;

class NullColumn;
class StructColumn;
class RelTableData;
struct ColumnCheckpointState;
class PageAllocator;
struct ChunkState;

class ColumnChunk;
class Column {
    friend class StringColumn;
    friend class StructColumn;
    friend class ListColumn;
    friend class RelTableData;

public:
    Column(std::string name, common::LogicalType dataType, FileHandle* dataFH, MemoryManager* mm,
        ShadowFile* shadowFile, bool enableCompression, bool requireNullColumn = true);
    Column(std::string name, common::PhysicalTypeID physicalType, FileHandle* dataFH,
        MemoryManager* mm, ShadowFile* shadowFile, bool enableCompression,
        bool requireNullColumn = true);

    virtual ~Column();

    void populateExtraChunkState(SegmentState& state) const;

    static std::unique_ptr<ColumnChunkData> flushChunkData(const ColumnChunkData& chunkData,
        PageAllocator& pageAllocator);
    static std::unique_ptr<ColumnChunkData> flushNonNestedChunkData(
        const ColumnChunkData& chunkData, PageAllocator& pageAllocator);
    static ColumnChunkMetadata flushData(const ColumnChunkData& chunkData,
        PageAllocator& pageAllocator);

    // Use lookupInternal to specialize
    void lookupValue(const ChunkState& state, common::offset_t nodeOffset,
        common::ValueVector* resultVector, uint32_t posInVector) const;

    // Scan from [offsetInChunk, offsetInChunk + length) (use scanInternal to specialize).
    //
    // The selectionVector in the resultVector's dataState should only select positions up to the
    // length parameter (E.g. if you want to scan just position 2047 you have to pass a length of
    // 2048; that is, like an unfiltered scan of 0-2047 but filtering everything but the value at
    // index 2047).
    // Primitive columns may scan more than the filtered values
    virtual void scan(const ChunkState& state, common::offset_t startOffsetInGroup,
        common::offset_t length, common::ValueVector* resultVector, uint64_t offsetInVector) const;
    // Scan from [offsetInChunk, offsetInChunk + length) (use scanInternal to specialize).
    // Appends to the end of the columnChunk
    void scan(const ChunkState& state, ColumnChunkData* columnChunk,
        common::offset_t offsetInChunk = 0, common::offset_t numValues = UINT64_MAX) const;
    // Scan from [offsetInChunk, offsetInChunk + length) (use scanInternal to specialize).
    // Appends to the end of the columnChunk
    virtual void scanSegment(const SegmentState& state, ColumnChunkData* columnChunk,
        common::offset_t offsetInSegment, common::offset_t numValue) const;
    // Scan to raw data (does not scan any nested data and should only be used on primitive columns)
    void scanSegment(const SegmentState& state, common::offset_t startOffsetInSegment,
        common::offset_t length, uint8_t* result) const;

    common::LogicalType& getDataType() { return dataType; }
    const common::LogicalType& getDataType() const { return dataType; }

    Column* getNullColumn() const;

    std::string_view getName() const { return name; }

    // Batch write to a set of sequential pages.
    void write(ColumnChunkData& persistentChunk, ChunkState& state, common::offset_t dstOffset,
        const ColumnChunkData& data, common::offset_t srcOffset, common::length_t numValues) const;

    virtual void writeSegment(ColumnChunkData& persistentChunk, SegmentState& state,
        common::offset_t dstOffsetInSegment, const ColumnChunkData& data,
        common::offset_t srcOffset, common::length_t numValues) const;

    // Append values to the end of the node group, resizing it if necessary
    // Expects bools to be one bool per bit (like ColumnChunkData)
    common::offset_t appendValues(ColumnChunkData& persistentChunk, SegmentState& state,
        const uint8_t* data, const common::NullMask* nullChunkData,
        common::offset_t numValues) const;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
    template<class TARGET>
    const TARGET& cast() const {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }

    // Return value is the new segments if segment splitting occurs during an out of place
    // checkpoint
    virtual std::vector<std::unique_ptr<ColumnChunkData>> checkpointSegment(
        ColumnCheckpointState&& checkpointState, PageAllocator& pageAllocator,
        bool canSplitSegment = true) const;

protected:
    // For a scan that includes a selectionVector, the startOffsetInVector should be considered to
    // be an offset for the selected positions within the selectionVector The offset of a given pos
    // from the selectionVector within the segment is equal to:
    //      startOffsetInSegment + pos - startOffsetInVector
    // Note that the positions in the selectionVector may not be in the range covered by the segment
    // Out of range positions should be ignored
    virtual void scanSegment(const SegmentState& state, common::offset_t startOffsetInSegment,
        common::row_idx_t numValuesToScan, common::ValueVector* resultVector,
        common::offset_t startOffsetInVector) const;

    virtual void lookupInternal(const SegmentState& state, common::offset_t offsetInSegment,
        common::ValueVector* resultVector, uint32_t posInVector) const;

    void writeValues(ChunkState& state, common::offset_t dstOffset, const uint8_t* data,
        const common::NullMask* nullChunkData, common::offset_t srcOffset = 0,
        common::offset_t numValues = 1) const;

    void writeValuesInternal(SegmentState& state, common::offset_t dstOffsetInSegment,
        const uint8_t* data, const common::NullMask* nullChunkData, common::offset_t srcOffset = 0,
        common::offset_t numValues = 1) const;

    void updateStatistics(ColumnChunkMetadata& metadata, common::offset_t maxIndex,
        const std::optional<StorageValue>& min, const std::optional<StorageValue>& max) const;

protected:
    bool isEndOffsetOutOfPagesCapacity(const ColumnChunkMetadata& metadata,
        common::offset_t endOffset) const;

    virtual bool canCheckpointInPlace(const SegmentState& state,
        const ColumnCheckpointState& checkpointState) const;

    void checkpointColumnChunkInPlace(SegmentState& state,
        const ColumnCheckpointState& checkpointState, PageAllocator& pageAllocator) const;

    void checkpointNullData(const ColumnCheckpointState& checkpointState,
        PageAllocator& pageAllocator) const;

    std::vector<std::unique_ptr<ColumnChunkData>> checkpointColumnChunkOutOfPlace(
        const SegmentState& state, const ColumnCheckpointState& checkpointState,
        PageAllocator& pageAllocator, bool canSplitSegment) const;

    // check if val is in range [start, end)
    static bool isInRange(uint64_t val, uint64_t start, uint64_t end) {
        return val >= start && val < end;
    }

protected:
    std::string name;
    common::LogicalType dataType;
    MemoryManager* mm;
    FileHandle* dataFH;
    ShadowFile* shadowFile;
    std::unique_ptr<NullColumn> nullColumn;
    read_values_to_vector_func_t readToVectorFunc;
    write_values_func_t writeFunc;
    read_values_to_page_func_t readToPageFunc;
    bool enableCompression;

    std::unique_ptr<ColumnReadWriter> columnReadWriter;
};

class InternalIDColumn final : public Column {
public:
    InternalIDColumn(std::string name, FileHandle* dataFH, MemoryManager* mm,
        ShadowFile* shadowFile, bool enableCompression);

    void scan(const ChunkState& state, common::offset_t startOffsetInGroup, common::offset_t length,
        common::ValueVector* resultVector, uint64_t offsetInVector) const override {
        Column::scan(state, startOffsetInGroup, length, resultVector, offsetInVector);
        populateCommonTableID(resultVector);
    }

    void lookupInternal(const SegmentState& state, common::offset_t offsetInSegment,
        common::ValueVector* resultVector, uint32_t posInVector) const override {
        Column::lookupInternal(state, offsetInSegment, resultVector, posInVector);
        populateCommonTableID(resultVector);
    }

    common::table_id_t getCommonTableID() const { return commonTableID; }
    // TODO(Guodong): This function should be removed through rewriting INTERNAL_ID as STRUCT.
    void setCommonTableID(common::table_id_t tableID) { commonTableID = tableID; }

private:
    void populateCommonTableID(const common::ValueVector* resultVector) const;

private:
    common::table_id_t commonTableID;
};

struct ColumnFactory {
    static std::unique_ptr<Column> createColumn(std::string name, common::LogicalType dataType,
        FileHandle* dataFH, MemoryManager* mm, ShadowFile* shadowFile, bool enableCompression);
    static std::unique_ptr<Column> createColumn(std::string name,
        common::PhysicalTypeID physicalType, FileHandle* dataFH, MemoryManager* mm,
        ShadowFile* shadowFile, bool enableCompression);
};

} // namespace storage
} // namespace lbug
