#include "storage/table/csr_chunked_node_group.h"

#include "common/serializer/deserializer.h"
#include "common/types/types.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/enums/residency_state.h"
#include "storage/page_allocator.h"
#include "storage/storage_utils.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/csr_node_group.h"
#include "transaction/transaction.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

CSRRegion::CSRRegion(idx_t regionIdx, idx_t level) : regionIdx{regionIdx}, level{level} {
    const auto leftLeafRegion = regionIdx << level;
    leftNodeOffset = leftLeafRegion << StorageConfig::CSR_LEAF_REGION_SIZE_LOG2;
    rightNodeOffset = leftNodeOffset + (StorageConfig::CSR_LEAF_REGION_SIZE << level) - 1;
    if (rightNodeOffset >= StorageConfig::NODE_GROUP_SIZE) {
        // The max right node offset should be NODE_GROUP_SIZE - 1.
        rightNodeOffset = StorageConfig::NODE_GROUP_SIZE - 1;
    }
}

bool CSRRegion::isWithin(const CSRRegion& other) const {
    if (other.level <= level) {
        return false;
    }
    const auto leftRegionIdx = getLeftLeafRegionIdx();
    const auto rightRegionIdx = getRightLeafRegionIdx();
    const auto otherLeftRegionIdx = other.getLeftLeafRegionIdx();
    const auto otherRightRegionIdx = other.getRightLeafRegionIdx();
    return leftRegionIdx >= otherLeftRegionIdx && rightRegionIdx <= otherRightRegionIdx;
}

CSRRegion CSRRegion::upgradeLevel(const std::vector<CSRRegion>& leafRegions,
    const CSRRegion& region) {
    const auto regionIdx = region.regionIdx >> 1;
    CSRRegion newRegion{regionIdx, region.level + 1};
    newRegion.hasUpdates.resize(region.hasUpdates.size(), false);
    const idx_t leftLeafRegionIdx = newRegion.getLeftLeafRegionIdx();
    const idx_t rightLeafRegionIdx = newRegion.getRightLeafRegionIdx();
    for (auto leafRegionIdx = leftLeafRegionIdx; leafRegionIdx <= rightLeafRegionIdx;
         leafRegionIdx++) {
        KU_ASSERT(leafRegionIdx < leafRegions.size());
        newRegion.sizeChange += leafRegions[leafRegionIdx].sizeChange;
        newRegion.hasPersistentDeletions |= leafRegions[leafRegionIdx].hasPersistentDeletions;
        newRegion.hasInsertions |= leafRegions[leafRegionIdx].hasInsertions;
        for (auto columnID = 0u; columnID < leafRegions[leafRegionIdx].hasUpdates.size();
             columnID++) {
            newRegion.hasUpdates[columnID] =
                static_cast<bool>(newRegion.hasUpdates[columnID]) ||
                static_cast<bool>(leafRegions[leafRegionIdx].hasUpdates[columnID]);
        }
    }
    return newRegion;
}

ChunkedCSRHeader::ChunkedCSRHeader(MemoryManager& memoryManager, bool enableCompression,
    uint64_t capacity, ResidencyState residencyState) {
    offset = std::make_unique<ColumnChunk>(memoryManager, LogicalType::UINT64(), capacity,
        enableCompression, residencyState, false);
    length = std::make_unique<ColumnChunk>(memoryManager, LogicalType::UINT64(), capacity,
        enableCompression, residencyState, false);
}

offset_t ChunkedCSRHeader::getStartCSROffset(offset_t nodeOffset) const {
    // TODO(Guodong): I think we can simplify the check here by getting rid of some of the
    // conditions.
    const auto numValues = offset->getNumValues();
    if (nodeOffset == 0 || numValues == 0) {
        return 0;
    }
    if (randomLookup) {
        return offset->getValue<offset_t>(0);
    }
    return offset->getValue<offset_t>(nodeOffset >= numValues ? (numValues - 1) : nodeOffset - 1);
}

offset_t ChunkedCSRHeader::getEndCSROffset(offset_t nodeOffset) const {
    // TODO(Guodong): I think we can simplify the check here by getting rid of some of the
    // conditions.
    const auto numValues = offset->getNumValues();
    if (numValues == 0) {
        return 0;
    }
    if (randomLookup) {
        return offset->getValue<offset_t>(nodeOffset == 0 ? 0 : 1);
    }
    return offset->getValue<offset_t>(nodeOffset >= numValues ? (numValues - 1) : nodeOffset);
}

length_t ChunkedCSRHeader::getCSRLength(offset_t nodeOffset) const {
    const auto offset = randomLookup ? 0 : nodeOffset;
    return offset >= length->getNumValues() ? 0 : length->getValue<length_t>(offset);
}

length_t ChunkedCSRHeader::getGapSize(offset_t nodeOffset) const {
    return getEndCSROffset(nodeOffset) - getStartCSROffset(nodeOffset) - getCSRLength(nodeOffset);
}

bool ChunkedCSRHeader::sanityCheck() const {
    if (offset->getNumValues() != length->getNumValues()) {
        return false;
    }
    if (offset->getNumValues() == 0) {
        return true;
    }
    if (offset->getValue<offset_t>(0) < length->getValue<length_t>(0)) {
        return false;
    }
    for (auto i = 1u; i < offset->getNumValues(); i++) {
        if (offset->getValue<offset_t>(i - 1) + length->getValue<length_t>(i) >
            offset->getValue<offset_t>(i)) {
            return false;
        }
    }
    return true;
}

offset_vec_t ChunkedCSRHeader::populateStartCSROffsetsFromLength(bool leaveGaps) const {
    const auto numNodes = length->getNumValues();
    const auto numLeafRegions = getNumRegions();
    offset_t leftCSROffset = 0;
    offset_vec_t rightCSROffsetOfRegions;
    rightCSROffsetOfRegions.reserve(numLeafRegions);
    for (auto regionIdx = 0u; regionIdx < numLeafRegions; regionIdx++) {
        CSRRegion region{regionIdx, 0 /* level*/};
        length_t numRelsInRegion = 0;
        const auto rightNodeOffset = std::min(region.rightNodeOffset, numNodes - 1);
        // Populate start csr offset for each node in the region.
        offset->mapValues<offset_t>(
            [&](auto& value, auto nodeOffset) {
                value = leftCSROffset + numRelsInRegion;
                numRelsInRegion += getCSRLength(nodeOffset);
            },
            region.leftNodeOffset, rightNodeOffset);
        // Update lastLeftCSROffset for next region.
        leftCSROffset += numRelsInRegion;
        if (leaveGaps) {
            leftCSROffset += computeGapFromLength(numRelsInRegion);
        }
        rightCSROffsetOfRegions.push_back(leftCSROffset);
    }
    return rightCSROffsetOfRegions;
}

void ChunkedCSRHeader::populateEndCSROffsetFromStartAndLength() const {
    [[maybe_unused]] const auto numNodes = length->getNumValues();
    KU_ASSERT(offset->getNumValues() == numNodes);
    // TODO(bmwinger): maybe there's a way of also vectorizing this for the length chunk, E.g. a
    // forEach over two values
    offset->mapValues<offset_t>(
        [&](offset_t& offset, auto i) { offset += length->getValue<length_t>(i); });
}

void ChunkedCSRHeader::finalizeCSRRegionEndOffsets(
    const offset_vec_t& rightCSROffsetOfRegions) const {
    const auto numNodes = length->getNumValues();
    const auto numLeafRegions = getNumRegions();
    KU_ASSERT(numLeafRegions == rightCSROffsetOfRegions.size());
    for (auto regionIdx = 0u; regionIdx < numLeafRegions; regionIdx++) {
        CSRRegion region{regionIdx, 0 /* level*/};
        const auto rightNodeOffset = std::min(region.rightNodeOffset, numNodes - 1);
        offset->setValue<offset_t>(rightCSROffsetOfRegions[regionIdx], rightNodeOffset);
    }
}

idx_t ChunkedCSRHeader::getNumRegions() const {
    const auto numNodes = length->getNumValues();
    KU_ASSERT(offset->getNumValues() == numNodes);
    return (numNodes + StorageConfig::CSR_LEAF_REGION_SIZE - 1) /
           StorageConfig::CSR_LEAF_REGION_SIZE;
}

void ChunkedCSRHeader::populateRegionCSROffsets(const CSRRegion& region,
    const ChunkedCSRHeader& oldHeader) const {
    KU_ASSERT(region.level <= CSRNodeGroup::DEFAULT_PACKED_CSR_INFO.calibratorTreeHeight);
    const auto leftNodeOffset = region.leftNodeOffset;
    const auto rightNodeOffset = region.rightNodeOffset;
    const auto leftCSROffset = oldHeader.getStartCSROffset(leftNodeOffset);
    const auto oldRightCSROffset = oldHeader.getEndCSROffset(rightNodeOffset);
    length_t numRelsInRegion = 0u;
    // TODO(bmwinger): should be able to vectorize this somewhat
    for (auto i = leftNodeOffset; i <= rightNodeOffset; i++) {
        numRelsInRegion += length->getValue<length_t>(i);
        offset->setValue<offset_t>(leftCSROffset + numRelsInRegion, i);
    }
    // We should keep the region stable and the old right CSR offset is the end of the region.
    KU_ASSERT(offset->getValue<offset_t>(rightNodeOffset) <= oldRightCSROffset);
    offset->setValue(oldRightCSROffset, rightNodeOffset);
}

void ChunkedCSRHeader::populateEndCSROffsets(const offset_vec_t& gaps) const {
    KU_ASSERT(offset->getNumValues() == length->getNumValues());
    KU_ASSERT(offset->getNumValues() == gaps.size());
    offset->mapValues<offset_t>([&](offset_t& offset, auto i) { offset = gaps[i]; });
}

length_t ChunkedCSRHeader::computeGapFromLength(length_t length) {
    return StorageUtils::divideAndRoundUpTo(length, StorageConstants::PACKED_CSR_DENSITY) - length;
}

std::unique_ptr<ChunkedNodeGroup> InMemChunkedCSRNodeGroup::flush(
    transaction::Transaction* transaction, PageAllocator& pageAllocator) {
    auto csrOffset = flushInternal(*csrHeader.offset, pageAllocator);
    auto csrLength = flushInternal(*csrHeader.length, pageAllocator);
    std::vector<std::unique_ptr<ColumnChunk>> flushedChunks(getNumColumns());
    for (auto i = 0u; i < getNumColumns(); i++) {
        flushedChunks[i] = flushInternal(getColumnChunk(i), pageAllocator);
    }
    ChunkedCSRHeader newCSRHeader{std::move(csrOffset), std::move(csrLength)};
    auto flushedChunkedGroup = std::make_unique<ChunkedCSRNodeGroup>(std::move(newCSRHeader),
        std::move(flushedChunks), 0 /*startRowIdx*/);
    flushedChunkedGroup->versionInfo = std::make_unique<VersionInfo>();
    KU_ASSERT(numRows == flushedChunkedGroup->getNumRows());
    flushedChunkedGroup->versionInfo->append(transaction->getID(), 0, numRows);
    return flushedChunkedGroup;
}

void ChunkedCSRNodeGroup ::reclaimStorage(PageAllocator& pageAllocator) const {
    ChunkedNodeGroup::reclaimStorage(pageAllocator);
    if (csrHeader.offset) {
        csrHeader.offset->reclaimStorage(pageAllocator);
    }
    if (csrHeader.length) {
        csrHeader.length->reclaimStorage(pageAllocator);
    }
}

void ChunkedCSRNodeGroup::scanCSRHeader(MemoryManager& memoryManager,
    CSRNodeGroupCheckpointState& csrState) const {
    if (!csrState.oldHeader) {
        csrState.oldHeader = std::make_unique<InMemChunkedCSRHeader>(memoryManager,
            false /*enableCompression*/, StorageConfig::NODE_GROUP_SIZE);
    }
    ChunkState headerChunkState;
    KU_ASSERT(csrHeader.offset->getResidencyState() == ResidencyState::ON_DISK);
    KU_ASSERT(csrHeader.length->getResidencyState() == ResidencyState::ON_DISK);
    csrHeader.offset->initializeScanState(headerChunkState, csrState.csrOffsetColumn);
    KU_ASSERT(csrState.csrOffsetColumn && csrState.csrLengthColumn);
    csrState.csrOffsetColumn->scan(headerChunkState, csrState.oldHeader->offset.get());
    csrHeader.length->initializeScanState(headerChunkState, csrState.csrLengthColumn);
    csrState.csrLengthColumn->scan(headerChunkState, csrState.oldHeader->length.get());
}

void ChunkedCSRNodeGroup::serialize(Serializer& serializer) const {
    KU_ASSERT(csrHeader.offset && csrHeader.length);
    serializer.writeDebuggingInfo("csr_header_offset");
    csrHeader.offset->serialize(serializer);
    serializer.writeDebuggingInfo("csr_header_length");
    csrHeader.length->serialize(serializer);
    ChunkedNodeGroup::serialize(serializer);
}

std::unique_ptr<ChunkedCSRNodeGroup> ChunkedCSRNodeGroup::deserialize(MemoryManager& memoryManager,
    Deserializer& deSer) {
    std::string key;
    deSer.validateDebuggingInfo(key, "csr_header_offset");
    auto offset = ColumnChunk::deserialize(memoryManager, deSer);
    deSer.validateDebuggingInfo(key, "csr_header_length");
    auto length = ColumnChunk::deserialize(memoryManager, deSer);
    // TODO(Guodong): Rework to reuse ChunkedNodeGroup::deserialize().
    std::vector<std::unique_ptr<ColumnChunk>> chunks;
    deSer.validateDebuggingInfo(key, "chunks");
    deSer.deserializeVectorOfPtrs<ColumnChunk>(chunks,
        [&](Deserializer& deser) { return ColumnChunk::deserialize(memoryManager, deser); });
    deSer.validateDebuggingInfo(key, "startRowIdx");
    row_idx_t startRowIdx = 0;
    deSer.deserializeValue<row_idx_t>(startRowIdx);
    auto chunkedGroup = std::make_unique<ChunkedCSRNodeGroup>(
        ChunkedCSRHeader{std::move(offset), std::move(length)}, std::move(chunks), startRowIdx);
    bool hasVersions = false;
    deSer.validateDebuggingInfo(key, "has_version_info");
    deSer.deserializeValue<bool>(hasVersions);
    if (hasVersions) {
        deSer.validateDebuggingInfo(key, "version_info");
        chunkedGroup->versionInfo = VersionInfo::deserialize(deSer);
    }
    return chunkedGroup;
}

ChunkedCSRNodeGroup::ChunkedCSRNodeGroup(InMemChunkedCSRNodeGroup& base,
    const std::vector<common::column_id_t>& selectedColumns)
    : ChunkedNodeGroup{base, selectedColumns},
      csrHeader{std::make_unique<ColumnChunk>(true /*enableCompression*/,
                    std::move(base.csrHeader.offset)),
          std::make_unique<ColumnChunk>(true /*enableCompression*/,
              std::move(base.csrHeader.length))} {}

void InMemChunkedCSRHeader::fillDefaultValues(const offset_t newNumValues) const {
    const auto lastCSROffset = getEndCSROffset(length->getNumValues() - 1);
    for (auto i = length->getNumValues(); i < newNumValues; i++) {
        offset->setValue<offset_t>(lastCSROffset, i);
        length->setValue<length_t>(0, i);
    }
    KU_ASSERT(
        offset->getNumValues() >= newNumValues && length->getNumValues() == offset->getNumValues());
}

InMemChunkedCSRHeader::InMemChunkedCSRHeader(MemoryManager& memoryManager, bool enableCompression,
    uint64_t capacity) {
    offset = ColumnChunkFactory::createColumnChunkData(memoryManager, LogicalType::UINT64(),
        enableCompression, capacity, ResidencyState::IN_MEMORY, false);
    length = ColumnChunkFactory::createColumnChunkData(memoryManager, LogicalType::UINT64(),
        enableCompression, capacity, ResidencyState::IN_MEMORY, false);
}

offset_t InMemChunkedCSRHeader::getStartCSROffset(offset_t nodeOffset) const {
    // TODO(Guodong): I think we can simplify the check here by getting rid of some of the
    // conditions.
    const auto numValues = offset->getNumValues();
    if (nodeOffset == 0 || numValues == 0) {
        return 0;
    }
    if (randomLookup) {
        return offset->getValue<offset_t>(0);
    }
    return offset->getValue<offset_t>(nodeOffset >= numValues ? (numValues - 1) : nodeOffset - 1);
}

offset_t InMemChunkedCSRHeader::getEndCSROffset(offset_t nodeOffset) const {
    // TODO(Guodong): I think we can simplify the check here by getting rid of some of the
    // conditions.
    const auto numValues = offset->getNumValues();
    if (numValues == 0) {
        return 0;
    }
    if (randomLookup) {
        return offset->getValue<offset_t>(nodeOffset == 0 ? 0 : 1);
    }
    return offset->getValue<offset_t>(nodeOffset >= numValues ? (numValues - 1) : nodeOffset);
}

length_t InMemChunkedCSRHeader::getCSRLength(offset_t nodeOffset) const {
    const auto offset = randomLookup ? 0 : nodeOffset;
    return offset >= length->getNumValues() ? 0 : length->getValue<length_t>(offset);
}

length_t InMemChunkedCSRHeader::getGapSize(offset_t nodeOffset) const {
    return getEndCSROffset(nodeOffset) - getStartCSROffset(nodeOffset) - getCSRLength(nodeOffset);
}

bool InMemChunkedCSRHeader::sanityCheck() const {
    if (offset->getNumValues() != length->getNumValues()) {
        return false;
    }
    if (offset->getNumValues() == 0) {
        return true;
    }
    if (offset->getValue<offset_t>(0) < length->getValue<length_t>(0)) {
        return false;
    }
    for (auto i = 1u; i < offset->getNumValues(); i++) {
        if (offset->getValue<offset_t>(i - 1) + length->getValue<length_t>(i) >
            offset->getValue<offset_t>(i)) {
            return false;
        }
    }
    return true;
}

void InMemChunkedCSRHeader::copyFrom(const InMemChunkedCSRHeader& other) const {
    KU_ASSERT(offset->getNumValues() == length->getNumValues());
    KU_ASSERT(other.offset->getNumValues() == other.length->getNumValues());
    KU_ASSERT(other.offset->getCapacity() == offset->getCapacity());
    const auto numOtherValues = other.offset->getNumValues();
    memcpy(offset->getData(), other.offset->getData(), numOtherValues * sizeof(offset_t));
    memcpy(length->getData(), other.length->getData(), numOtherValues * sizeof(length_t));
    const auto lastOffsetInOtherHeader = other.getEndCSROffset(numOtherValues);
    const auto numValues = offset->getNumValues();
    for (auto i = numOtherValues; i < numValues; i++) {
        offset->setValue<offset_t>(lastOffsetInOtherHeader, i);
        length->setValue<length_t>(0, i);
    }
}

offset_vec_t InMemChunkedCSRHeader::populateStartCSROffsetsFromLength(bool leaveGaps) const {
    const auto numNodes = length->getNumValues();
    const auto numLeafRegions = getNumRegions();
    offset_t leftCSROffset = 0;
    offset_vec_t rightCSROffsetOfRegions;
    rightCSROffsetOfRegions.reserve(numLeafRegions);
    for (auto regionIdx = 0u; regionIdx < numLeafRegions; regionIdx++) {
        CSRRegion region{regionIdx, 0 /* level*/};
        length_t numRelsInRegion = 0;
        const auto rightNodeOffset = std::min(region.rightNodeOffset, numNodes - 1);
        // Populate start csr offset for each node in the region.
        for (auto nodeOffset = region.leftNodeOffset; nodeOffset <= rightNodeOffset; nodeOffset++) {
            offset->setValue<offset_t>(leftCSROffset + numRelsInRegion, nodeOffset);
            numRelsInRegion += getCSRLength(nodeOffset);
        }
        // Update lastLeftCSROffset for next region.
        leftCSROffset += numRelsInRegion;
        if (leaveGaps) {
            leftCSROffset += computeGapFromLength(numRelsInRegion);
        }
        rightCSROffsetOfRegions.push_back(leftCSROffset);
    }
    return rightCSROffsetOfRegions;
}

void InMemChunkedCSRHeader::populateEndCSROffsetFromStartAndLength() const {
    const auto numNodes = length->getNumValues();
    KU_ASSERT(offset->getNumValues() == numNodes);
    const auto csrOffsets = reinterpret_cast<offset_t*>(offset->getData());
    const auto csrLengths = reinterpret_cast<length_t*>(length->getData());
    for (auto i = 0u; i < numNodes; i++) {
        csrOffsets[i] = csrOffsets[i] + csrLengths[i];
    }
}

void InMemChunkedCSRHeader::finalizeCSRRegionEndOffsets(
    const offset_vec_t& rightCSROffsetOfRegions) const {
    const auto numNodes = length->getNumValues();
    const auto numLeafRegions = getNumRegions();
    KU_ASSERT(numLeafRegions == rightCSROffsetOfRegions.size());
    for (auto regionIdx = 0u; regionIdx < numLeafRegions; regionIdx++) {
        CSRRegion region{regionIdx, 0 /* level*/};
        const auto rightNodeOffset = std::min(region.rightNodeOffset, numNodes - 1);
        offset->setValue<offset_t>(rightCSROffsetOfRegions[regionIdx], rightNodeOffset);
    }
}

idx_t InMemChunkedCSRHeader::getNumRegions() const {
    const auto numNodes = length->getNumValues();
    KU_ASSERT(offset->getNumValues() == numNodes);
    return (numNodes + StorageConfig::CSR_LEAF_REGION_SIZE - 1) /
           StorageConfig::CSR_LEAF_REGION_SIZE;
}

void InMemChunkedCSRHeader::populateRegionCSROffsets(const CSRRegion& region,
    const InMemChunkedCSRHeader& oldHeader) const {
    KU_ASSERT(region.level <= CSRNodeGroup::DEFAULT_PACKED_CSR_INFO.calibratorTreeHeight);
    const auto leftNodeOffset = region.leftNodeOffset;
    const auto rightNodeOffset = region.rightNodeOffset;
    const auto leftCSROffset = oldHeader.getStartCSROffset(leftNodeOffset);
    const auto oldRightCSROffset = oldHeader.getEndCSROffset(rightNodeOffset);
    const auto csrOffsets = reinterpret_cast<offset_t*>(offset->getData());
    const auto csrLengths = reinterpret_cast<length_t*>(length->getData());
    length_t numRelsInRegion = 0u;
    for (auto i = leftNodeOffset; i <= rightNodeOffset; i++) {
        numRelsInRegion += csrLengths[i];
        csrOffsets[i] = leftCSROffset + numRelsInRegion;
    }
    // We should keep the region stable and the old right CSR offset is the end of the region.
    KU_ASSERT(csrOffsets[rightNodeOffset] <= oldRightCSROffset);
    csrOffsets[rightNodeOffset] = oldRightCSROffset;
}

void InMemChunkedCSRHeader::populateEndCSROffsets(const offset_vec_t& gaps) const {
    const auto csrOffsets = reinterpret_cast<offset_t*>(offset->getData());
    KU_ASSERT(offset->getNumValues() == length->getNumValues());
    KU_ASSERT(offset->getNumValues() == gaps.size());
    for (auto i = 0u; i < offset->getNumValues(); i++) {
        csrOffsets[i] += gaps[i];
    }
}

length_t InMemChunkedCSRHeader::computeGapFromLength(length_t length) {
    return StorageUtils::divideAndRoundUpTo(length, StorageConstants::PACKED_CSR_DENSITY) - length;
}

} // namespace storage
} // namespace lbug
