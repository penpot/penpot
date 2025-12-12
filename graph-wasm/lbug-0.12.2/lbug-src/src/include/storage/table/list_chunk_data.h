#pragma once

#include "common/data_chunk/sel_vector.h"
#include "common/types/types.h"
#include "storage/table/column_chunk_data.h"

namespace lbug {
namespace storage {
class MemoryManager;

class LBUG_API ListChunkData final : public ColumnChunkData {
public:
    static constexpr common::idx_t SIZE_COLUMN_CHILD_READ_STATE_IDX = 0;
    static constexpr common::idx_t DATA_COLUMN_CHILD_READ_STATE_IDX = 1;
    static constexpr common::idx_t OFFSET_COLUMN_CHILD_READ_STATE_IDX = 2;
    static constexpr size_t CHILD_COLUMN_COUNT = 3;

    ListChunkData(MemoryManager& mm, common::LogicalType dataType, uint64_t capacity,
        bool enableCompression, ResidencyState residencyState);
    ListChunkData(MemoryManager& mm, common::LogicalType dataType, bool enableCompression,
        const ColumnChunkMetadata& metadata);

    ColumnChunkData* getOffsetColumnChunk() const { return offsetColumnChunk.get(); }

    ColumnChunkData* getDataColumnChunk() const { return dataColumnChunk.get(); }
    std::unique_ptr<ColumnChunkData> moveDataColumnChunk() { return std::move(dataColumnChunk); }

    ColumnChunkData* getSizeColumnChunk() const { return sizeColumnChunk.get(); }
    std::unique_ptr<ColumnChunkData> moveSizeColumnChunk() { return std::move(sizeColumnChunk); }

    void setOffsetColumnChunk(std::unique_ptr<ColumnChunkData> offsetColumnChunk_) {
        offsetColumnChunk = std::move(offsetColumnChunk_);
    }
    void setDataColumnChunk(std::unique_ptr<ColumnChunkData> dataColumnChunk_) {
        dataColumnChunk = std::move(dataColumnChunk_);
    }
    void setSizeColumnChunk(std::unique_ptr<ColumnChunkData> sizeColumnChunk_) {
        sizeColumnChunk = std::move(sizeColumnChunk_);
    }

    void resetToEmpty() override;

    void setNumValues(uint64_t numValues_) override {
        ColumnChunkData::setNumValues(numValues_);
        sizeColumnChunk->setNumValues(numValues_);
        offsetColumnChunk->setNumValues(numValues_);
    }

    void resetNumValuesFromMetadata() override;
    void syncNumValues() override {
        numValues = offsetColumnChunk->getNumValues();
        metadata.numValues = numValues;
    }

    void append(common::ValueVector* vector, const common::SelectionView& selVector) override;

    void initializeScanState(SegmentState& state, const Column* column) const override;
    void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector) const override;
    void lookup(common::offset_t offsetInChunk, common::ValueVector& output,
        common::sel_t posInOutputVector) const override;

    // Note: `write` assumes that no `append` will be called afterward.
    void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk) override;
    void write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets,
        common::RelMultiplicity multiplicity) override;
    void write(const ColumnChunkData* srcChunk, common::offset_t srcOffsetInChunk,
        common::offset_t dstOffsetInChunk, common::offset_t numValuesToCopy) override;

    void resizeDataColumnChunk(uint64_t numValues) const { dataColumnChunk->resize(numValues); }

    void setToInMemory() override {
        ColumnChunkData::setToInMemory();
        sizeColumnChunk->setToInMemory();
        offsetColumnChunk->setToInMemory();
        dataColumnChunk->setToInMemory();
        KU_ASSERT(offsetColumnChunk->getNumValues() == numValues);
    }
    void resize(uint64_t newCapacity) override {
        ColumnChunkData::resize(newCapacity);
        sizeColumnChunk->resize(newCapacity);
        offsetColumnChunk->resize(newCapacity);
    }

    void resizeWithoutPreserve(uint64_t newCapacity) override {
        ColumnChunkData::resizeWithoutPreserve(newCapacity);
        sizeColumnChunk->resizeWithoutPreserve(newCapacity);
        offsetColumnChunk->resizeWithoutPreserve(newCapacity);
    }

    common::offset_t getListStartOffset(common::offset_t offset) const;

    common::offset_t getListEndOffset(common::offset_t offset) const;

    common::list_size_t getListSize(common::offset_t offset) const;

    void resetOffset();
    void resetFromOtherChunk(ListChunkData* other);
    void finalize() override;
    bool isOffsetsConsecutiveAndSortedAscending(uint64_t startPos, uint64_t endPos) const;
    bool sanityCheck() const override;

    uint64_t getEstimatedMemoryUsage() const override;

    void serialize(common::Serializer& serializer) const override;
    static void deserialize(common::Deserializer& deSer, ColumnChunkData& chunkData);

    void flush(PageAllocator& pageAllocator) override;
    uint64_t getMinimumSizeOnDisk() const override;
    uint64_t getSizeOnDisk() const override;
    uint64_t getSizeOnDiskInMemoryStats() const override;
    void reclaimStorage(PageAllocator& pageAllocator) override;

protected:
    void copyListValues(const common::list_entry_t& entry, common::ValueVector* dataVector);

private:
    void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend) override;

    void appendNullList();

    void setOffsetChunkValue(common::offset_t val, common::offset_t pos);

protected:
    std::unique_ptr<ColumnChunkData> offsetColumnChunk;
    std::unique_ptr<ColumnChunkData> sizeColumnChunk;
    std::unique_ptr<ColumnChunkData> dataColumnChunk;
    // we use checkOffsetSortedAsc flag to indicate that we do not trigger random write
    bool checkOffsetSortedAsc;
};

} // namespace storage
} // namespace lbug
