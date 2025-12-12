#pragma once

#include "common/assert.h"
#include "common/data_chunk/sel_vector.h"
#include "common/types/types.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/dictionary_chunk.h"

namespace lbug {
namespace storage {
class MemoryManager;

class StringChunkData final : public ColumnChunkData {
public:
    static constexpr common::idx_t DATA_COLUMN_CHILD_READ_STATE_IDX = 0;
    static constexpr common::idx_t OFFSET_COLUMN_CHILD_READ_STATE_IDX = 1;
    static constexpr common::idx_t INDEX_COLUMN_CHILD_READ_STATE_IDX = 2;
    static constexpr common::idx_t CHILD_COLUMN_COUNT = 3;

    StringChunkData(MemoryManager& mm, common::LogicalType dataType, uint64_t capacity,
        bool enableCompression, ResidencyState residencyState);
    StringChunkData(MemoryManager& mm, bool enableCompression, const ColumnChunkMetadata& metadata);
    void resetToEmpty() override;

    void append(common::ValueVector* vector, const common::SelectionView& selView) override;
    void append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
        uint32_t numValuesToAppend) override;
    ColumnChunkData* getIndexColumnChunk();
    const ColumnChunkData* getIndexColumnChunk() const;

    void initializeScanState(SegmentState& state, const Column* column) const override;
    void scan(common::ValueVector& output, common::offset_t offset, common::length_t length,
        common::sel_t posInOutputVector = 0) const override;
    void lookup(common::offset_t offsetInChunk, common::ValueVector& output,
        common::sel_t posInOutputVector) const override;

    void write(const common::ValueVector* vector, common::offset_t offsetInVector,
        common::offset_t offsetInChunk) override;
    void write(ColumnChunkData* chunk, ColumnChunkData* dstOffsets,
        common::RelMultiplicity multiplicity) override;
    void write(const ColumnChunkData* srcChunk, common::offset_t srcOffsetInChunk,
        common::offset_t dstOffsetInChunk, common::offset_t numValuesToCopy) override;

    template<typename T>
    T getValue(common::offset_t /*pos*/) const {
        KU_UNREACHABLE;
    }

    uint64_t getStringLength(common::offset_t pos) const {
        const auto index = indexColumnChunk->getValue<DictionaryChunk::string_index_t>(pos);
        return dictionaryChunk->getStringLength(index);
    }

    void setIndexChunk(std::unique_ptr<ColumnChunkData> indexChunk) {
        indexColumnChunk = std::move(indexChunk);
    }
    DictionaryChunk& getDictionaryChunk() { return *dictionaryChunk; }
    const DictionaryChunk& getDictionaryChunk() const { return *dictionaryChunk; }

    void finalize() override;

    void flush(PageAllocator& pageAllocator) override;
    uint64_t getSizeOnDisk() const override;
    uint64_t getMinimumSizeOnDisk() const override;
    uint64_t getSizeOnDiskInMemoryStats() const override;
    void reclaimStorage(PageAllocator& pageAllocator) override;

    void resetNumValuesFromMetadata() override;
    void syncNumValues() override {
        numValues = indexColumnChunk->getNumValues();
        metadata.numValues = numValues;
    }

    void setToInMemory() override;
    void resize(uint64_t newCapacity) override;
    void resizeWithoutPreserve(uint64_t newCapacity) override;
    uint64_t getEstimatedMemoryUsage() const override;

    void serialize(common::Serializer& serializer) const override;
    static void deserialize(common::Deserializer& deSer, ColumnChunkData& chunkData);

private:
    void appendStringColumnChunk(const StringChunkData* other,
        common::offset_t startPosInOtherChunk, uint32_t numValuesToAppend);

    void setValueFromString(std::string_view value, uint64_t pos);

    void updateNumValues(size_t newValue);

    void setNumValues(uint64_t numValues) override {
        ColumnChunkData::setNumValues(numValues);
        indexColumnChunk->setNumValues(numValues);
        needFinalize = true;
    }

private:
    std::unique_ptr<ColumnChunkData> indexColumnChunk;

    std::unique_ptr<DictionaryChunk> dictionaryChunk;
    // If we never update a value, we don't need to prune unused strings in finalize
    bool needFinalize;
};

// STRING
template<>
std::string StringChunkData::getValue<std::string>(common::offset_t pos) const;
template<>
std::string_view StringChunkData::getValue<std::string_view>(common::offset_t pos) const;

} // namespace storage
} // namespace lbug
