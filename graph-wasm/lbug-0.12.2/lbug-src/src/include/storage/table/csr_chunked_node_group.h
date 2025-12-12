#pragma once

#include <algorithm>

#include "storage/enums/residency_state.h"
#include "storage/table/chunked_node_group.h"
#include "storage/table/column_chunk.h"

namespace lbug {
namespace storage {
class PageAllocator;
class MemoryManager;

struct CSRRegion {
    common::idx_t regionIdx = common::INVALID_IDX;
    common::idx_t level = common::INVALID_IDX;
    common::offset_t leftNodeOffset = common::INVALID_OFFSET;
    common::offset_t rightNodeOffset = common::INVALID_OFFSET;
    int64_t sizeChange = 0;
    // Track if there is any updates to persistent data in this region per column in table.
    // Note: should be accessed with columnID.
    std::vector<bool> hasUpdates;
    // Note: `sizeChange` equal to 0 is not enough to indicate the region has no insert or
    // delete. It might just be num of insertions are equal to num of deletions.
    // hasInsertions is true if there are insertions that are not deleted yet in this region.
    bool hasInsertions = false;
    bool hasPersistentDeletions = false;

    CSRRegion(common::idx_t regionIdx, common::idx_t level);

    bool needCheckpoint() const {
        return hasInsertions || hasPersistentDeletions ||
               std::any_of(hasUpdates.begin(), hasUpdates.end(),
                   [](bool hasUpdate) { return hasUpdate; });
    }
    bool needCheckpointColumn(common::column_id_t columnID) const {
        KU_ASSERT(columnID < hasUpdates.size());
        return hasInsertions || hasPersistentDeletions || hasUpdates[columnID];
    }
    bool hasDeletionsOrInsertions() const { return hasInsertions || hasPersistentDeletions; }
    common::idx_t getLeftLeafRegionIdx() const { return regionIdx << level; }
    common::idx_t getRightLeafRegionIdx() const {
        const auto rightRegionIdx =
            getLeftLeafRegionIdx() + (static_cast<common::idx_t>(1) << level) - 1;
        constexpr auto maxNumRegions =
            common::StorageConfig::NODE_GROUP_SIZE / common::StorageConfig::CSR_LEAF_REGION_SIZE;
        if (rightRegionIdx >= maxNumRegions) {
            return maxNumRegions - 1;
        }
        return rightRegionIdx;
    }
    // Return true if other is within the realm of this region.
    bool isWithin(const CSRRegion& other) const;

    static CSRRegion upgradeLevel(const std::vector<CSRRegion>& leafRegions,
        const CSRRegion& region);
};

struct LBUG_API InMemChunkedCSRHeader {
    std::unique_ptr<ColumnChunkData> offset;
    std::unique_ptr<ColumnChunkData> length;
    bool randomLookup = false;

    InMemChunkedCSRHeader(MemoryManager& memoryManager, bool enableCompression, uint64_t capacity);
    InMemChunkedCSRHeader(std::unique_ptr<ColumnChunkData> offset,
        std::unique_ptr<ColumnChunkData> length)
        : offset{std::move(offset)}, length{std::move(length)} {
        KU_ASSERT(this->offset && this->length);
    }

    common::offset_t getStartCSROffset(common::offset_t nodeOffset) const;
    common::offset_t getEndCSROffset(common::offset_t nodeOffset) const;
    common::length_t getCSRLength(common::offset_t nodeOffset) const;
    common::length_t getGapSize(common::length_t length) const;

    bool sanityCheck() const;
    void copyFrom(const InMemChunkedCSRHeader& other) const;
    void fillDefaultValues(common::offset_t newNumValues) const;
    void setNumValues(const common::offset_t numValues) const {
        offset->setNumValues(numValues);
        length->setNumValues(numValues);
    }

    // Return a vector of CSR offsets for the end of each CSR region.
    common::offset_vec_t populateStartCSROffsetsFromLength(bool leaveGaps) const;
    void populateEndCSROffsetFromStartAndLength() const;
    void finalizeCSRRegionEndOffsets(const common::offset_vec_t& rightCSROffsetOfRegions) const;
    void populateRegionCSROffsets(const CSRRegion& region,
        const InMemChunkedCSRHeader& oldHeader) const;
    void populateEndCSROffsets(const common::offset_vec_t& gaps) const;
    common::idx_t getNumRegions() const;

private:
    static common::length_t computeGapFromLength(common::length_t length);
};

struct ChunkedCSRHeader {
    std::unique_ptr<ColumnChunk> offset;
    std::unique_ptr<ColumnChunk> length;
    bool randomLookup = false;

    ChunkedCSRHeader(MemoryManager& memoryManager, bool enableCompression, uint64_t capacity,
        ResidencyState residencyState);
    ChunkedCSRHeader(bool enableCompression, InMemChunkedCSRHeader&& other)
        : offset{std::make_unique<ColumnChunk>(enableCompression, std::move(other.offset))},
          length{std::make_unique<ColumnChunk>(enableCompression, std::move(other.length))},
          randomLookup{other.randomLookup} {}
    ChunkedCSRHeader(std::unique_ptr<ColumnChunk> offset, std::unique_ptr<ColumnChunk> length)
        : offset{std::move(offset)}, length{std::move(length)} {
        KU_ASSERT(this->offset && this->length);
    }

    common::offset_t getStartCSROffset(common::offset_t nodeOffset) const;
    common::offset_t getEndCSROffset(common::offset_t nodeOffset) const;
    common::length_t getCSRLength(common::offset_t nodeOffset) const;
    common::length_t getGapSize(common::length_t length) const;

    bool sanityCheck() const;

    // Return a vector of CSR offsets for the end of each CSR region.
    common::offset_vec_t populateStartCSROffsetsFromLength(bool leaveGaps) const;
    void populateEndCSROffsetFromStartAndLength() const;
    void finalizeCSRRegionEndOffsets(const common::offset_vec_t& rightCSROffsetOfRegions) const;
    void populateRegionCSROffsets(const CSRRegion& region, const ChunkedCSRHeader& oldHeader) const;
    void populateEndCSROffsets(const common::offset_vec_t& gaps) const;
    common::idx_t getNumRegions() const;

private:
    static common::length_t computeGapFromLength(common::length_t length);
};

class InMemChunkedCSRNodeGroup;

struct CSRNodeGroupCheckpointState;
class ChunkedCSRNodeGroup final : public ChunkedNodeGroup {
    friend class InMemChunkedCSRNodeGroup;

public:
    ChunkedCSRNodeGroup(MemoryManager& mm, const std::vector<common::LogicalType>& columnTypes,
        bool enableCompression, uint64_t capacity, common::offset_t startOffset,
        ResidencyState residencyState)
        : ChunkedNodeGroup{mm, columnTypes, enableCompression, capacity, startOffset,
              residencyState, NodeGroupDataFormat::CSR},
          csrHeader{mm, enableCompression, common::StorageConfig::NODE_GROUP_SIZE, residencyState} {
    }
    ChunkedCSRNodeGroup(InMemChunkedCSRNodeGroup& base,
        const std::vector<common::column_id_t>& selectedColumns);
    ChunkedCSRNodeGroup(ChunkedCSRNodeGroup& base,
        const std::vector<common::column_id_t>& selectedColumns)
        : ChunkedNodeGroup{base, selectedColumns}, csrHeader{std::move(base.csrHeader)} {}
    ChunkedCSRNodeGroup(MemoryManager& mm, ChunkedCSRNodeGroup& base,
        std::span<const common::LogicalType> columnTypes,
        std::span<const common::column_id_t> baseColumnIDs)
        : ChunkedNodeGroup(mm, base, columnTypes, baseColumnIDs),
          csrHeader(std::move(base.csrHeader)) {}
    ChunkedCSRNodeGroup(ChunkedCSRHeader csrHeader,
        std::vector<std::unique_ptr<ColumnChunk>> chunks, common::row_idx_t startRowIdx)
        : ChunkedNodeGroup{std::move(chunks), startRowIdx, NodeGroupDataFormat::CSR},
          csrHeader{std::move(csrHeader)} {}

    ChunkedCSRHeader& getCSRHeader() { return csrHeader; }
    const ChunkedCSRHeader& getCSRHeader() const { return csrHeader; }

    void serialize(common::Serializer& serializer) const override;
    static std::unique_ptr<ChunkedCSRNodeGroup> deserialize(MemoryManager& memoryManager,
        common::Deserializer& deSer);

    void scanCSRHeader(MemoryManager& memoryManager, CSRNodeGroupCheckpointState& csrState) const;

    void reclaimStorage(PageAllocator& pageAllocator) const override;

private:
    ChunkedCSRHeader csrHeader;
};

class InMemChunkedCSRNodeGroup final : public InMemChunkedNodeGroup {
    friend class ChunkedCSRNodeGroup;

public:
    InMemChunkedCSRNodeGroup(MemoryManager& mm, const std::vector<common::LogicalType>& columnTypes,
        bool enableCompression, uint64_t capacity, common::offset_t startOffset)
        : InMemChunkedNodeGroup{mm, columnTypes, enableCompression, capacity, startOffset},
          csrHeader{mm, enableCompression, common::StorageConfig::NODE_GROUP_SIZE} {}

    InMemChunkedCSRNodeGroup(InMemChunkedCSRNodeGroup& base,
        const std::vector<common::column_id_t>& selectedColumns)
        : InMemChunkedNodeGroup{base, selectedColumns}, csrHeader{std::move(base.csrHeader)} {}

    InMemChunkedCSRHeader& getCSRHeader() { return csrHeader; }
    const InMemChunkedCSRHeader& getCSRHeader() const { return csrHeader; }

    // this does not override ChunkedNodeGroup::merge() since clang-tidy analyzer
    // seems to struggle with detecting the std::move of the header unless this is inlined
    void mergeChunkedCSRGroup(InMemChunkedCSRNodeGroup& base,
        const std::vector<common::column_id_t>& columnsToMergeInto) {
        InMemChunkedNodeGroup::merge(base, columnsToMergeInto);
        csrHeader = InMemChunkedCSRHeader(std::move(base.csrHeader.offset),
            std::move(base.csrHeader.length));
    }

    void writeToColumnChunk(common::idx_t chunkIdx, common::idx_t vectorIdx,
        const std::vector<std::unique_ptr<ColumnChunkData>>& data,
        ColumnChunkData& offsetChunk) override {
        chunks[chunkIdx]->write(data[vectorIdx].get(), &offsetChunk, common::RelMultiplicity::MANY);
    }

    std::unique_ptr<ChunkedNodeGroup> flush(transaction::Transaction* transaction,
        PageAllocator& pageAllocator) override;

private:
    InMemChunkedCSRHeader csrHeader;
};

} // namespace storage
} // namespace lbug
