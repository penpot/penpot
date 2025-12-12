#pragma once

#include "common/data_chunk/sel_vector.h"
#include "common/types/types.h"
#include "storage/table/column_chunk_data.h"

namespace lbug {
namespace storage {
class MemoryManager;

class StructChunkData final : public ColumnChunkData {
public:
    StructChunkData(MemoryManager& mm, common::LogicalType dataType, uint64_t capacity,
        bool enableCompression, ResidencyState residencyState);
    StructChunkData(MemoryManager& mm, common::LogicalType dataType, bool enableCompression,
        const ColumnChunkMetadata& metadata);

    ColumnChunkData* getChild(common::idx_t childIdx) {
        KU_ASSERT(childIdx < childChunks.size());
        return childChunks[childIdx].get();
    }
    std::unique_ptr<ColumnChunkData> moveChild(common::idx_t childIdx) {
        KU_ASSERT(childIdx < childChunks.size());
        return std::move(childChunks[childIdx]);
    }

    void finalize() override;

    uint64_t getEstimatedMemoryUsage() const override;

    void resetNumValuesFromMetadata() override;
    void syncNumValues() override {
        KU_ASSERT(!childChunks.empty());
        numValues = childChunks[0]->getNumValues();
        metadata.numValues = numValues;
    }

    void serialize(common::Serializer& serializer) const override;
    static void deserialize(common::Deserializer& deSer, ColumnChunkData& chunkData);

    common::idx_t getNumChildren() const { return childChunks.size(); }
    const ColumnChunkData& getChild(common::idx_t childIdx) const {
        KU_ASSERT(childIdx < childChunks.size());
        return *childChunks[childIdx];
    }
    void setChild(common::idx_t childIdx, std::unique_ptr<ColumnChunkData> childChunk) {
        KU_ASSERT(childIdx < childChunks.size());
        childChunks[childIdx] = std::move(childChunk);
    }

    void flush(PageAllocator& pageAllocator) override;
    uint64_t getSizeOnDisk() const override;
    uint64_t getMinimumSizeOnDisk() const override;
    uint64_t getSizeOnDiskInMemoryStats() const override;
    void reclaimStorage(PageAllocator& pageAllocator) override;

protected:
    void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend) override;
    void append(common::ValueVector* vector, const common::SelectionView& selView) override;

    void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector = 0) const override;
    void lookup(common::offset_t offsetInChunk, common::ValueVector& output,
        common::sel_t posInOutputVector) const override;
    void initializeScanState(SegmentState& state, const Column* column) const override;

    void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk) override;
    void write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets,
        common::RelMultiplicity multiplicity) override;
    void write(const ColumnChunkData* srcChunk, common::offset_t srcOffsetInChunk,
        common::offset_t dstOffsetInChunk, common::offset_t numValuesToCopy) override;

    void setToInMemory() override;
    void resize(uint64_t newCapacity) override;
    void resizeWithoutPreserve(uint64_t newCapacity) override;

    void resetToEmpty() override;
    void resetToAllNull() override;

    bool numValuesSanityCheck() const override;

    void setNumValues(uint64_t numValues) override {
        ColumnChunkData::setNumValues(numValues);
        for (auto& childChunk : childChunks) {
            childChunk->setNumValues(numValues);
        }
    }

private:
    std::vector<std::unique_ptr<ColumnChunkData>> childChunks;
};

} // namespace storage
} // namespace lbug
