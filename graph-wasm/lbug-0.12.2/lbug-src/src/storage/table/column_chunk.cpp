#include "storage/table/column_chunk.h"

#include <algorithm>
#include <memory>

#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/vector/value_vector.h"
#include "main/client_context.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/enums/residency_state.h"
#include "storage/page_allocator.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/column_chunk_scanner.h"
#include "storage/table/combined_chunk_scanner.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

void ChunkState::reclaimAllocatedPages(PageAllocator& pageAllocator) const {
    for (auto& state : segmentStates) {
        state.reclaimAllocatedPages(pageAllocator);
    }
}

std::pair<const SegmentState*, common::offset_t> ChunkState::findSegment(
    common::offset_t offsetInChunk) const {
    auto [iter, offsetInSegment] = genericFindSegment(std::span(segmentStates), offsetInChunk);
    if (iter == std::span(segmentStates).end()) {
        return std::make_pair(nullptr, 0);
    }
    return std::make_pair(&*iter, offsetInSegment);
}

ColumnChunk::ColumnChunk(MemoryManager& mm, LogicalType&& dataType, uint64_t capacity,
    bool enableCompression, ResidencyState residencyState, bool initializeToZero)
    : enableCompression{enableCompression} {
    data.push_back(ColumnChunkFactory::createColumnChunkData(mm, std::move(dataType),
        enableCompression, capacity, residencyState, true, initializeToZero));
    KU_ASSERT(residencyState != ResidencyState::ON_DISK);
}

ColumnChunk::ColumnChunk(MemoryManager& mm, LogicalType&& dataType, bool enableCompression,
    ColumnChunkMetadata metadata)
    : enableCompression{enableCompression} {
    data.push_back(ColumnChunkFactory::createColumnChunkData(mm, std::move(dataType),
        enableCompression, metadata, true, true));
}

ColumnChunk::ColumnChunk(bool enableCompression, std::unique_ptr<ColumnChunkData> data)
    : enableCompression{enableCompression}, data{} {
    this->data.push_back(std::move(data));
}
ColumnChunk::ColumnChunk(bool enableCompression,
    std::vector<std::unique_ptr<ColumnChunkData>> segments)
    : enableCompression{enableCompression}, data{std::move(segments)} {}

void ColumnChunk::initializeScanState(ChunkState& state, const Column* column) const {
    state.column = column;
    state.segmentStates.resize(data.size());
    for (size_t i = 0; i < data.size(); i++) {
        data[i]->initializeScanState(state.segmentStates[i], column);
    }
}

void ColumnChunk::scan(const Transaction* transaction, const ChunkState& state, ValueVector& output,
    offset_t offsetInChunk, length_t length) const {
    // Check if there is deletions or insertions. If so, update selVector based on transaction.
    switch (getResidencyState()) {
    case ResidencyState::IN_MEMORY: {
        rangeSegments(offsetInChunk, length,
            [&](auto& segment, auto offsetInSegment, auto lengthInSegment, auto dstOffset) {
                segment->scan(output, offsetInSegment, lengthInSegment, dstOffset);
            });
    } break;
    case ResidencyState::ON_DISK: {
        state.column->scan(state, offsetInChunk, length, &output, 0);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    updateInfo.scan(transaction, output, offsetInChunk, length);
}

static void scanPersistentSegments(ChunkState& chunkState, ColumnChunkScanner& output,
    common::offset_t startRow, common::offset_t numRows) {
    KU_ASSERT(output.getNumValues() == 0);
    [[maybe_unused]] uint64_t numValuesScanned = chunkState.rangeSegments(startRow, numRows,
        [&](auto& segmentState, auto offsetInSegment, auto lengthInSegment, auto) {
            output.scanSegment(offsetInSegment, lengthInSegment,
                [&chunkState, &segmentState](ColumnChunkData& outputChunk, offset_t offsetInSegment,
                    offset_t lengthInSegment) {
                    chunkState.column->scanSegment(segmentState, &outputChunk, offsetInSegment,
                        lengthInSegment);
                });
        });
    KU_ASSERT(output.getNumValues() == numValuesScanned);
}

void ColumnChunk::scanInMemSegments(ColumnChunkScanner& output, common::offset_t startRow,
    common::offset_t numRows) const {
    rangeSegments(startRow, numRows,
        [&](auto& segment, auto offsetInSegment, auto lengthInSegment, auto) {
            output.scanSegment(offsetInSegment, lengthInSegment,
                [&segment](ColumnChunkData& outputChunk, offset_t offsetInSegment,
                    offset_t lengthInSegment) {
                    outputChunk.append(segment.get(), offsetInSegment, lengthInSegment);
                });
        });
}

template<ResidencyState SCAN_RESIDENCY_STATE>
void ColumnChunk::scanCommitted(const Transaction* transaction, ChunkState& chunkState,
    ColumnChunkScanner& output, row_idx_t startRow, row_idx_t numRows) const {
    auto numValuesInChunk = getNumValues();
    if (numRows == INVALID_ROW_IDX || startRow + numRows > numValuesInChunk) {
        numRows = numValuesInChunk - startRow;
    }
    if (numRows == 0 || startRow >= numValuesInChunk) {
        return;
    }
    const auto residencyState = getResidencyState();
    if (SCAN_RESIDENCY_STATE == residencyState) {
        if constexpr (SCAN_RESIDENCY_STATE == ResidencyState::ON_DISK) {
            scanPersistentSegments(chunkState, output, startRow, numRows);
        } else {
            static_assert(SCAN_RESIDENCY_STATE == ResidencyState::IN_MEMORY);
            scanInMemSegments(output, startRow, numRows);
        }
        output.applyCommittedUpdates(updateInfo, transaction, startRow, numRows);
    }
}

template void ColumnChunk::scanCommitted<ResidencyState::ON_DISK>(const Transaction* transaction,
    ChunkState& chunkState, ColumnChunkScanner& output, row_idx_t startRow,
    row_idx_t numRows) const;
template void ColumnChunk::scanCommitted<ResidencyState::IN_MEMORY>(const Transaction* transaction,
    ChunkState& chunkState, ColumnChunkScanner& output, row_idx_t startRow,
    row_idx_t numRows) const;

template<ResidencyState SCAN_RESIDENCY_STATE>
void ColumnChunk::scanCommitted(const Transaction* transaction, ChunkState& chunkState,
    ColumnChunkData& output, row_idx_t startRow, row_idx_t numRows) const {
    CombinedChunkScanner scanner{output};
    scanCommitted<SCAN_RESIDENCY_STATE>(transaction, chunkState, scanner, startRow, numRows);
}

template void ColumnChunk::scanCommitted<ResidencyState::ON_DISK>(const Transaction* transaction,
    ChunkState& chunkState, ColumnChunkData& output, row_idx_t startRow, row_idx_t numRows) const;
template void ColumnChunk::scanCommitted<ResidencyState::IN_MEMORY>(const Transaction* transaction,
    ChunkState& chunkState, ColumnChunkData& output, row_idx_t startRow, row_idx_t numRows) const;

bool ColumnChunk::hasUpdates(const Transaction* transaction, row_idx_t startRow,
    length_t numRows) const {
    return updateInfo.hasUpdates(transaction, startRow, numRows);
}

void ColumnChunk::lookup(const Transaction* transaction, const ChunkState& state,
    offset_t rowInChunk, ValueVector& output, sel_t posInOutputVector) const {
    switch (getResidencyState()) {
    case ResidencyState::IN_MEMORY: {
        rangeSegments(rowInChunk, 1, [&](auto& segment, auto offsetInSegment, auto, auto) {
            segment->lookup(offsetInSegment, output, posInOutputVector);
        });
    } break;
    case ResidencyState::ON_DISK: {
        state.column->lookupValue(state, rowInChunk, &output, posInOutputVector);
    } break;
    }
    updateInfo.lookup(transaction, rowInChunk, output, posInOutputVector);
}

void ColumnChunk::update(const Transaction* transaction, offset_t offsetInChunk,
    const ValueVector& values) {
    if (transaction->getType() == TransactionType::DUMMY) {
        rangeSegments(offsetInChunk, 1, [&](auto& segment, auto offsetInSegment, auto, auto) {
            segment->write(&values, values.state->getSelVector().getSelectedPositions()[0],
                offsetInSegment);
        });
        return;
    }

    const auto vectorIdx = offsetInChunk / DEFAULT_VECTOR_CAPACITY;
    const auto rowIdxInVector = offsetInChunk % DEFAULT_VECTOR_CAPACITY;
    auto& vectorUpdateInfo = updateInfo.update(data.front()->getMemoryManager(), transaction,
        vectorIdx, rowIdxInVector, values);
    transaction->pushVectorUpdateInfo(updateInfo, vectorIdx, vectorUpdateInfo,
        transaction->getID());
}

MergedColumnChunkStats ColumnChunk::getMergedColumnChunkStats() const {
    KU_ASSERT(!updateInfo.isSet());
    auto baseStats = MergedColumnChunkStats{ColumnChunkStats{}, true, true};
    for (auto& segment : data) {
        // TODO: Replace with a function that modifies the existing stats in-place?
        auto segmentStats = segment->getMergedColumnChunkStats();
        baseStats.merge(segmentStats, segment->getDataType().getPhysicalType());
    }
    return baseStats;
}

void ColumnChunk::serialize(Serializer& serializer) const {
    serializer.writeDebuggingInfo("enable_compression");
    serializer.write<bool>(enableCompression);
    serializer.write<uint64_t>(data.size());
    for (auto& segment : data) {
        segment->serialize(serializer);
    }
}

std::unique_ptr<ColumnChunk> ColumnChunk::deserialize(MemoryManager& mm, Deserializer& deSer) {
    std::string key;
    bool enableCompression = false;
    deSer.validateDebuggingInfo(key, "enable_compression");
    deSer.deserializeValue<bool>(enableCompression);
    uint64_t numSegments = 0;
    deSer.deserializeValue(numSegments);
    std::vector<std::unique_ptr<ColumnChunkData>> segments;
    for (uint64_t i = 0; i < numSegments; i++) {
        segments.push_back(ColumnChunkData::deserialize(mm, deSer));
    }
    return std::make_unique<ColumnChunk>(enableCompression, std::move(segments));
}

row_idx_t ColumnChunk::getNumUpdatedRows(const Transaction* transaction) const {
    return updateInfo.getNumUpdatedRows(transaction);
}

void ColumnChunk::reclaimStorage(PageAllocator& pageAllocator) const {
    for (const auto& segment : data) {
        segment->reclaimStorage(pageAllocator);
    }
}

void ColumnChunk::append(common::ValueVector* vector, const common::SelectionView& selView) {
    data.back()->append(vector, selView);
}

void ColumnChunk::append(const ColumnChunk* other, common::offset_t startPosInOtherChunk,
    uint32_t numValuesToAppend) {
    for (auto& otherSegment : other->data) {
        if (numValuesToAppend == 0) {
            return;
        }
        if (otherSegment->getNumValues() < startPosInOtherChunk) {
            startPosInOtherChunk -= otherSegment->getNumValues();
        } else {
            auto numValuesToAppendInSegment =
                std::min(otherSegment->getNumValues(), uint64_t{numValuesToAppend});
            append(otherSegment.get(), startPosInOtherChunk, numValuesToAppendInSegment);
            numValuesToAppend -= numValuesToAppendInSegment;
            startPosInOtherChunk = 0;
        }
    }
}

void ColumnChunk::append(const ColumnChunkData* other, common::offset_t startPosInOtherChunk,
    uint32_t numValuesToAppend) {
    data.back()->append(other, startPosInOtherChunk, numValuesToAppend);
}

void ColumnChunk::write(Column& column, ChunkState& state, offset_t dstOffset,
    const ColumnChunkData& dataToWrite, offset_t srcOffset, common::length_t numValues) {
    auto segment = data.begin();
    auto offsetInSegment = dstOffset;
    while (segment->get()->getNumValues() < offsetInSegment) {
        offsetInSegment -= segment->get()->getNumValues();
        segment++;
    }
    while (numValues > 0) {
        auto numValuesToWriteInSegment =
            std::min(numValues, segment->get()->getNumValues()) - offsetInSegment;
        column.write(*segment->get(), state, offsetInSegment, dataToWrite, srcOffset,
            numValuesToWriteInSegment);
        offsetInSegment = 0;
        numValues -= numValuesToWriteInSegment;
        srcOffset += numValuesToWriteInSegment;
    }
}

void ColumnChunk::checkpoint(Column& column,
    std::vector<ChunkCheckpointState>&& chunkCheckpointStates, PageAllocator& pageAllocator) {
    offset_t segmentStart = 0;
    for (size_t i = 0; i < data.size(); i++) {
        std::vector<SegmentCheckpointState> segmentCheckpointStates;
        auto& segment = data[i];
        KU_ASSERT(segment->getResidencyState() == ResidencyState::ON_DISK);
        for (auto& state : chunkCheckpointStates) {
            const bool isLastSegment = (i == data.size() - 1);
            if (state.startRow + state.numRows > segmentStart &&
                (isLastSegment || state.startRow < segmentStart + segment->getNumValues())) {
                const auto startOffset = std::max(state.startRow, segmentStart);
                // Generally, we only want to checkpoint the overlapping parts of the old segment
                // and the new chunk. This is to prevent having duplicate segments. However, for the
                // last old segment we allow extending it to account for any insertions we have made
                // in the current checkpoint.
                const auto endOffset = isLastSegment ? state.startRow + state.numRows :
                                                       std::min(state.startRow + state.numRows,
                                                           segmentStart + segment->getNumValues());

                const auto startOffsetInSegment = startOffset - segmentStart;
                const auto startRowInChunk = startOffset - state.startRow;
                segmentCheckpointStates.push_back({*state.chunkData, startRowInChunk,
                    startOffsetInSegment, endOffset - startOffset});
            }
        }
        auto segmentEnd = segmentStart + segment->getNumValues();
        // If the segment was split during checkpointing we need to insert the new segments into the
        // ColumnChunk
        auto newSegments = column.checkpointSegment(
            ColumnCheckpointState(*segment, std::move(segmentCheckpointStates)), pageAllocator);
        if (!newSegments.empty()) {
            auto oldSize = data.size();
            data.resize(data.size() - 1 + newSegments.size());
            std::move_backward(data.begin() + i, data.begin() + oldSize, data.end());
            for (size_t j = 0; j < newSegments.size(); j++) {
                data[i + j] = std::move(newSegments[j]);
            }
            // We want to increment by a total of newSegments.size() but we increment i at the end
            // of each loop body
            i += newSegments.size() - 1;
        }
        segmentStart = segmentEnd;
    }
}

} // namespace storage
} // namespace lbug
