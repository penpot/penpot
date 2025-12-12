#include "storage/table/chunked_node_group.h"

#include <exception>

#include "common/assert.h"
#include "common/types/types.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/buffer_manager/spiller.h"
#include "storage/enums/residency_state.h"
#include "storage/page_allocator.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/column_chunk_scanner.h"
#include "storage/table/node_table.h"

using namespace lbug::common;
using namespace lbug::transaction;

namespace lbug {
namespace storage {

template<class Chunk>
static void handleAppendException(std::vector<std::unique_ptr<Chunk>>& chunks, uint64_t numRows) {
    // After an exception is thrown other threads may continue to work on this chunked group for a
    // while before they are interrupted
    // Although the changes will eventually be rolled back
    // We reset the state of the chunk so later changes won't corrupt any data
    // Due to the numValues in column chunks not matching the number of rows
    for (const auto& chunk : chunks) {
        chunk->truncate(numRows);
    }
    std::rethrow_exception(std::current_exception());
}

ChunkedNodeGroup::ChunkedNodeGroup(std::vector<std::unique_ptr<ColumnChunk>> chunks,
    row_idx_t startRowIdx, NodeGroupDataFormat format)
    : format{format}, startRowIdx{startRowIdx}, chunks{std::move(chunks)} {
    KU_ASSERT(!this->chunks.empty());
    residencyState = this->chunks[0]->getResidencyState();
    numRows = this->chunks[0]->getNumValues();
    capacity = numRows;
    for (auto columnID = 1u; columnID < this->chunks.size(); columnID++) {
        KU_ASSERT(this->chunks[columnID]->getNumValues() == numRows);
        KU_ASSERT(this->chunks[columnID]->getResidencyState() == residencyState);
    }
}

ChunkedNodeGroup::ChunkedNodeGroup(ChunkedNodeGroup& base,
    const std::vector<column_id_t>& selectedColumns)
    : format{base.format}, residencyState{base.residencyState}, startRowIdx{base.startRowIdx},
      capacity{base.capacity}, numRows{base.numRows.load()} {
    chunks.resize(selectedColumns.size());
    for (auto i = 0u; i < selectedColumns.size(); i++) {
        auto columnID = selectedColumns[i];
        KU_ASSERT(columnID < base.getNumColumns());
        chunks[i] = base.moveColumnChunk(columnID);
    }
}

ChunkedNodeGroup::ChunkedNodeGroup(InMemChunkedNodeGroup& base,
    const std::vector<column_id_t>& selectedColumns, NodeGroupDataFormat format)
    : format{format}, residencyState{ResidencyState::IN_MEMORY}, startRowIdx{base.getStartRowIdx()},
      capacity{base.getCapacity()}, numRows{base.getNumRows()} {
    chunks.resize(selectedColumns.size());
    for (auto i = 0u; i < selectedColumns.size(); i++) {
        auto columnID = selectedColumns[i];
        KU_ASSERT(columnID < base.getNumColumns());
        chunks[i] = std::make_unique<ColumnChunk>(true /*enableCompression*/,
            base.moveColumnChunk(columnID));
    }
}

ChunkedNodeGroup::ChunkedNodeGroup(MemoryManager& mm, const std::vector<LogicalType>& columnTypes,
    bool enableCompression, uint64_t capacity, row_idx_t startRowIdx, ResidencyState residencyState,
    NodeGroupDataFormat format)
    : format{format}, residencyState{residencyState}, startRowIdx{startRowIdx}, capacity{capacity},
      numRows{0} {
    chunks.reserve(columnTypes.size());
    for (auto& type : columnTypes) {
        chunks.push_back(std::make_unique<ColumnChunk>(mm, type.copy(), capacity, enableCompression,
            residencyState));
    }
}

ChunkedNodeGroup::ChunkedNodeGroup(MemoryManager& mm, ChunkedNodeGroup& base,
    std::span<const LogicalType> columnTypes, std::span<const column_id_t> baseColumnIDs)
    : format{base.format}, residencyState{base.residencyState}, startRowIdx{base.startRowIdx},
      capacity{base.capacity}, numRows{base.numRows.load()},
      versionInfo(std::move(base.versionInfo)) {
    bool enableCompression = false;
    KU_ASSERT(!baseColumnIDs.empty());

    chunks.resize(columnTypes.size());

    KU_ASSERT(base.getNumColumns() == baseColumnIDs.size());
    for (column_id_t i = 0; i < baseColumnIDs.size(); ++i) {
        auto baseColumnID = baseColumnIDs[i];
        KU_ASSERT(baseColumnID < chunks.size());
        chunks[baseColumnID] = base.moveColumnChunk(i);
        enableCompression = chunks[baseColumnID]->isCompressionEnabled();
        KU_ASSERT(chunks[baseColumnID]->getDataType().getPhysicalType() ==
                  columnTypes[baseColumnID].getPhysicalType());
    }

    for (column_id_t i = 0; i < columnTypes.size(); ++i) {
        if (chunks[i] == nullptr) {
            chunks[i] = std::make_unique<ColumnChunk>(mm, columnTypes[i].copy(), 0,
                enableCompression, ResidencyState::IN_MEMORY);
        }
    }
}

void ChunkedNodeGroup::resetNumRowsFromChunks() {
    KU_ASSERT(residencyState == ResidencyState::ON_DISK);
    KU_ASSERT(!chunks.empty());
    numRows = getColumnChunk(0).getNumValues();
    capacity = numRows;
    for (auto i = 1u; i < getNumColumns(); i++) {
        KU_ASSERT(numRows == getColumnChunk(i).getNumValues());
    }
}

void ChunkedNodeGroup::resetVersionAndUpdateInfo() {
    if (versionInfo) {
        versionInfo.reset();
    }
    for (const auto& chunk : chunks) {
        chunk->resetUpdateInfo();
    }
}

void ChunkedNodeGroup::truncate(const offset_t numRows_) {
    KU_ASSERT(numRows >= numRows_);
    for (const auto& chunk : chunks) {
        chunk->truncate(numRows_);
    }
    numRows = numRows_;
}

void InMemChunkedNodeGroup::setNumRows(const offset_t numRows_) {
    for (const auto& chunk : chunks) {
        chunk->setNumValues(numRows_);
    }
    numRows = numRows_;
}

uint64_t ChunkedNodeGroup::append(const Transaction* transaction,
    const std::vector<ValueVector*>& columnVectors, row_idx_t startRowInVectors,
    uint64_t numValuesToAppend) {
    KU_ASSERT(residencyState != ResidencyState::ON_DISK);
    KU_ASSERT(columnVectors.size() == chunks.size());
    const auto numRowsToAppendInChunk = std::min(numValuesToAppend, capacity - numRows);
    try {
        for (auto i = 0u; i < columnVectors.size(); i++) {
            const auto columnVector = columnVectors[i];
            chunks[i]->append(columnVector, columnVector->state->getSelVector().slice(
                                                startRowInVectors, numRowsToAppendInChunk));
        }
    } catch ([[maybe_unused]] std::exception& e) {
        handleAppendException(chunks, numRows);
    }
    if (transaction->shouldAppendToUndoBuffer()) {
        if (!versionInfo) {
            versionInfo = std::make_unique<VersionInfo>();
        }
        versionInfo->append(transaction->getID(), numRows, numRowsToAppendInChunk);
    }
    numRows += numRowsToAppendInChunk;
    return numRowsToAppendInChunk;
}

offset_t ChunkedNodeGroup::append(const Transaction* transaction,
    const std::vector<column_id_t>& columnIDs, const ChunkedNodeGroup& other,
    offset_t offsetInOtherNodeGroup, offset_t numRowsToAppend) {
    KU_ASSERT(residencyState == ResidencyState::IN_MEMORY);
    KU_ASSERT(other.chunks.size() == chunks.size());
    std::vector<const ColumnChunk*> chunksToAppend(other.chunks.size());
    for (auto i = 0u; i < chunks.size(); i++) {
        chunksToAppend[i] = other.chunks[i].get();
    }
    return append(transaction, columnIDs, chunksToAppend, offsetInOtherNodeGroup, numRowsToAppend);
}

offset_t ChunkedNodeGroup::append(const Transaction* transaction,
    const std::vector<column_id_t>& columnIDs, const InMemChunkedNodeGroup& other,
    offset_t offsetInOtherNodeGroup, offset_t numRowsToAppend) {
    KU_ASSERT(residencyState == ResidencyState::IN_MEMORY);
    KU_ASSERT(other.chunks.size() == chunks.size());
    std::vector<const ColumnChunkData*> chunksToAppend(other.chunks.size());
    for (auto i = 0u; i < chunks.size(); i++) {
        chunksToAppend[i] = other.chunks[i].get();
    }
    return append(transaction, columnIDs, chunksToAppend, offsetInOtherNodeGroup, numRowsToAppend);
}

offset_t ChunkedNodeGroup::append(const Transaction* transaction,
    const std::vector<column_id_t>& columnIDs, std::span<const ColumnChunkData*> other,
    offset_t offsetInOtherNodeGroup, offset_t numRowsToAppend) {
    KU_ASSERT(residencyState == ResidencyState::IN_MEMORY);
    KU_ASSERT(other.size() == columnIDs.size());
    const auto numToAppendInChunkedGroup = std::min(numRowsToAppend, capacity - numRows);
    try {
        for (auto i = 0u; i < columnIDs.size(); i++) {
            auto columnID = columnIDs[i];
            KU_ASSERT(columnID < chunks.size());
            chunks[columnID]->append(other[i], offsetInOtherNodeGroup, numToAppendInChunkedGroup);
        }
    } catch ([[maybe_unused]] std::exception& e) {
        handleAppendException(chunks, numRows);
    }
    if (transaction->getID() != Transaction::DUMMY_TRANSACTION_ID) {
        if (!versionInfo) {
            versionInfo = std::make_unique<VersionInfo>();
        }
        versionInfo->append(transaction->getID(), numRows, numToAppendInChunkedGroup);
    }
    numRows += numToAppendInChunkedGroup;
    return numToAppendInChunkedGroup;
}

offset_t ChunkedNodeGroup::append(const Transaction* transaction,
    const std::vector<column_id_t>& columnIDs, std::span<const ColumnChunk*> other,
    offset_t offsetInOtherNodeGroup, offset_t numRowsToAppend) {
    KU_ASSERT(residencyState == ResidencyState::IN_MEMORY);
    KU_ASSERT(other.size() == columnIDs.size());
    const auto numToAppendInChunkedGroup = std::min(numRowsToAppend, capacity - numRows);
    try {
        for (auto i = 0u; i < columnIDs.size(); i++) {
            auto columnID = columnIDs[i];
            KU_ASSERT(columnID < chunks.size());
            chunks[columnID]->append(other[i], offsetInOtherNodeGroup, numToAppendInChunkedGroup);
        }
    } catch ([[maybe_unused]] std::exception& e) {
        handleAppendException(chunks, numRows);
    }
    if (transaction->shouldAppendToUndoBuffer()) {
        if (!versionInfo) {
            versionInfo = std::make_unique<VersionInfo>();
        }
        versionInfo->append(transaction->getID(), numRows, numToAppendInChunkedGroup);
    }
    numRows += numToAppendInChunkedGroup;
    return numToAppendInChunkedGroup;
}

void InMemChunkedNodeGroup::write(const InMemChunkedNodeGroup& data, column_id_t offsetColumnID) {
    KU_ASSERT(data.chunks.size() == chunks.size() + 1);
    auto& offsetChunk = data.chunks[offsetColumnID];
    column_id_t columnID = 0, chunkIdx = 0;
    for (auto i = 0u; i < data.chunks.size(); i++) {
        if (i == offsetColumnID) {
            columnID++;
            continue;
        }
        KU_ASSERT(columnID < data.chunks.size());
        writeToColumnChunk(chunkIdx, columnID, data.chunks, *offsetChunk);
        chunkIdx++;
        columnID++;
    }
    numRows = chunks[0]->getNumValues();
    for (auto i = 1u; i < chunks.size(); i++) {
        KU_ASSERT(numRows == chunks[i]->getNumValues());
    }
}

static ZoneMapCheckResult getZoneMapResult(const TableScanState& scanState,
    const std::vector<std::unique_ptr<ColumnChunk>>& chunks) {
    if (!scanState.columnPredicateSets.empty()) {
        for (auto i = 0u; i < scanState.columnIDs.size(); i++) {
            const auto columnID = scanState.columnIDs[i];
            if (columnID == INVALID_COLUMN_ID || columnID == ROW_IDX_COLUMN_ID) {
                continue;
            }

            KU_ASSERT(i < scanState.columnPredicateSets.size());
            if (chunks[columnID]->hasUpdates()) {
                // With updates, we need to merge with update data for the correct stats, which can
                // be slow if there are lots of updates. We defer this for now.
                return ZoneMapCheckResult::ALWAYS_SCAN;
            }
            const auto columnZoneMapResult = scanState.columnPredicateSets[i].checkZoneMap(
                chunks[columnID]->getMergedColumnChunkStats());
            if (columnZoneMapResult == ZoneMapCheckResult::SKIP_SCAN) {
                return ZoneMapCheckResult::SKIP_SCAN;
            }
        }
    }
    return ZoneMapCheckResult::ALWAYS_SCAN;
}

void ChunkedNodeGroup::scan(const Transaction* transaction, const TableScanState& scanState,
    const NodeGroupScanState& nodeGroupScanState, offset_t rowIdxInGroup,
    length_t numRowsToScan) const {
    KU_ASSERT(rowIdxInGroup + numRowsToScan <= numRows);
    auto& anchorSelVector = scanState.outState->getSelVectorUnsafe();
    if (getZoneMapResult(scanState, chunks) == ZoneMapCheckResult::SKIP_SCAN) {
        anchorSelVector.setToFiltered(0);
        return;
    }

    if (versionInfo) {
        versionInfo->getSelVectorToScan(transaction->getStartTS(), transaction->getID(),
            anchorSelVector, rowIdxInGroup, numRowsToScan);
    } else {
        anchorSelVector.setToUnfiltered(numRowsToScan);
    }

    if (anchorSelVector.getSelSize() > 0) {
        for (auto i = 0u; i < scanState.columnIDs.size(); i++) {
            const auto columnID = scanState.columnIDs[i];
            if (columnID == INVALID_COLUMN_ID) {
                scanState.outputVectors[i]->setAllNull();
                continue;
            }
            if (columnID == ROW_IDX_COLUMN_ID) {
                for (auto rowIdx = 0u; rowIdx < numRowsToScan; rowIdx++) {
                    scanState.rowIdxVector->setValue<row_idx_t>(rowIdx,
                        rowIdx + rowIdxInGroup + startRowIdx);
                }
                continue;
            }
            KU_ASSERT(columnID < chunks.size());
            chunks[columnID]->scan(transaction, nodeGroupScanState.chunkStates[i],
                *scanState.outputVectors[i], rowIdxInGroup, numRowsToScan);
        }
    }
}

template<ResidencyState SCAN_RESIDENCY_STATE>
void ChunkedNodeGroup::scanCommitted(Transaction* transaction, TableScanState& scanState,
    InMemChunkedNodeGroup& output) const {
    if (residencyState != SCAN_RESIDENCY_STATE) {
        return;
    }
    for (auto i = 0u; i < scanState.columnIDs.size(); i++) {
        const auto columnID = scanState.columnIDs[i];
        chunks[columnID]->scanCommitted<SCAN_RESIDENCY_STATE>(transaction,
            scanState.nodeGroupScanState->chunkStates[i], output.getColumnChunk(i));
    }
}

template void ChunkedNodeGroup::scanCommitted<ResidencyState::ON_DISK>(Transaction* transaction,
    TableScanState& scanState, InMemChunkedNodeGroup& output) const;
template void ChunkedNodeGroup::scanCommitted<ResidencyState::IN_MEMORY>(Transaction* transaction,
    TableScanState& scanState, InMemChunkedNodeGroup& output) const;

bool ChunkedNodeGroup::hasDeletions(const Transaction* transaction) const {
    return versionInfo && versionInfo->hasDeletions(transaction);
}

row_idx_t ChunkedNodeGroup::getNumUpdatedRows(const Transaction* transaction,
    column_id_t columnID) {
    return getColumnChunk(columnID).getNumUpdatedRows(transaction);
}

bool ChunkedNodeGroup::lookup(const Transaction* transaction, const TableScanState& state,
    const NodeGroupScanState& nodeGroupScanState, offset_t rowIdxInChunk, sel_t posInOutput) const {
    KU_ASSERT(rowIdxInChunk + 1 <= numRows);
    const bool hasValuesToRead = versionInfo ? versionInfo->isSelected(transaction->getStartTS(),
                                                   transaction->getID(), rowIdxInChunk) :
                                               true;
    if (!hasValuesToRead) {
        return false;
    }
    for (auto i = 0u; i < state.columnIDs.size(); i++) {
        const auto columnID = state.columnIDs[i];
        if (columnID == INVALID_COLUMN_ID) {
            state.outputVectors[i]->setAllNull();
            continue;
        }
        if (columnID == ROW_IDX_COLUMN_ID) {
            state.rowIdxVector->setValue<row_idx_t>(
                state.rowIdxVector->state->getSelVector()[posInOutput],
                rowIdxInChunk + startRowIdx);
            continue;
        }
        KU_ASSERT(columnID < chunks.size());
        KU_ASSERT(i < nodeGroupScanState.chunkStates.size());
        chunks[columnID]->lookup(transaction, nodeGroupScanState.chunkStates[i], rowIdxInChunk,
            *state.outputVectors[i], state.outputVectors[i]->state->getSelVector()[posInOutput]);
    }
    return true;
}

void ChunkedNodeGroup::update(const Transaction* transaction, row_idx_t rowIdxInChunk,
    column_id_t columnID, const ValueVector& propertyVector) {
    getColumnChunk(columnID).update(transaction, rowIdxInChunk, propertyVector);
}

bool ChunkedNodeGroup::delete_(const Transaction* transaction, row_idx_t rowIdxInChunk) {
    if (!versionInfo) {
        versionInfo = std::make_unique<VersionInfo>();
    }
    return versionInfo->delete_(transaction->getID(), rowIdxInChunk);
}

void ChunkedNodeGroup::addColumn(MemoryManager& mm, const TableAddColumnState& addColumnState,
    bool enableCompression, PageAllocator* pageAllocator, ColumnStats* newColumnStats) {
    auto& dataType = addColumnState.propertyDefinition.getType();
    chunks.push_back(std::make_unique<ColumnChunk>(mm, dataType.copy(), capacity, enableCompression,
        ResidencyState::IN_MEMORY));
    auto numExistingRows = getNumRows();
    chunks.back()->populateWithDefaultVal(addColumnState.defaultEvaluator, numExistingRows,
        newColumnStats);
    if (residencyState == ResidencyState::ON_DISK) {
        KU_ASSERT(pageAllocator);
        chunks.back()->flush(*pageAllocator);
    }
}

bool ChunkedNodeGroup::isDeleted(const Transaction* transaction, row_idx_t rowInChunk) const {
    if (!versionInfo) {
        return false;
    }
    return versionInfo->isDeleted(transaction, rowInChunk);
}

bool ChunkedNodeGroup::isInserted(const Transaction* transaction, row_idx_t rowInChunk) const {
    if (!versionInfo) {
        return rowInChunk < getNumRows();
    }
    return versionInfo->isInserted(transaction, rowInChunk);
}

bool ChunkedNodeGroup::hasAnyUpdates(const Transaction* transaction, column_id_t columnID,
    row_idx_t startRow, length_t numRowsToCheck) const {
    return getColumnChunk(columnID).hasUpdates(transaction, startRow, numRowsToCheck);
}

row_idx_t ChunkedNodeGroup::getNumDeletions(const Transaction* transaction, row_idx_t startRow,
    length_t numRowsToCheck) const {
    if (versionInfo) {
        return versionInfo->getNumDeletions(transaction, startRow, numRowsToCheck);
    }
    return 0;
}

std::unique_ptr<ColumnChunk> InMemChunkedNodeGroup::flushInternal(ColumnChunkData& chunk,
    PageAllocator& pageAllocator) {
    // Finalize is necessary prior to splitting for strings and lists so that pruned values
    // don't have an impact on the number/size of segments It should not be necessary after
    // splitting since the function is used to prune unused values (or duplicated dictionary
    // entries in the case of strings) and those will never be introduced when splitting.
    chunk.finalize();
    if (chunk.shouldSplit()) {
        auto splitSegments = chunk.split(true /*new segments are always the max size if possible*/);
        std::vector<std::unique_ptr<ColumnChunkData>> flushedSegments;
        flushedSegments.reserve(splitSegments.size());
        for (auto& segment : splitSegments) {
            // TODO(bmwinger): This should be removed when splitting works predictively instead of
            // backtracking if we copy too many values
            // It's only needed to prune values from string/list chunks which were truncated
            segment->finalize();
            flushedSegments.push_back(Column::flushChunkData(*segment, pageAllocator));
        }
        return std::make_unique<ColumnChunk>(chunk.isCompressionEnabled(),
            std::move(flushedSegments));
    } else {
        return std::make_unique<ColumnChunk>(chunk.isCompressionEnabled(),
            Column::flushChunkData(chunk, pageAllocator));
    }
}

std::unique_ptr<ChunkedNodeGroup> InMemChunkedNodeGroup::flush(Transaction* transaction,
    PageAllocator& pageAllocator) {
    std::vector<std::unique_ptr<ColumnChunk>> flushedChunks(getNumColumns());
    for (auto i = 0u; i < getNumColumns(); i++) {
        flushedChunks[i] = flushInternal(getColumnChunk(i), pageAllocator);
    }
    auto flushedChunkedGroup =
        std::make_unique<ChunkedNodeGroup>(std::move(flushedChunks), 0 /*startRowIdx*/);
    flushedChunkedGroup->versionInfo = std::make_unique<VersionInfo>();
    KU_ASSERT(flushedChunkedGroup->getNumRows() == numRows);
    flushedChunkedGroup->versionInfo->append(transaction->getID(), 0, numRows);
    return flushedChunkedGroup;
}

std::unique_ptr<ChunkedNodeGroup> ChunkedNodeGroup::flushEmpty(MemoryManager& mm,
    const std::vector<common::LogicalType>& columnTypes, bool enableCompression, uint64_t capacity,
    common::row_idx_t startRowIdx, PageAllocator& pageAllocator) {
    auto emptyGroup = std::make_unique<ChunkedNodeGroup>(mm, columnTypes, enableCompression,
        capacity, startRowIdx, ResidencyState::IN_MEMORY);
    for (auto i = 0u; i < columnTypes.size(); i++) {
        emptyGroup->getColumnChunk(i).flush(pageAllocator);
    }
    // Reset residencyState and numRows after flushing.
    emptyGroup->residencyState = ResidencyState::ON_DISK;
    return emptyGroup;
}

uint64_t ChunkedNodeGroup::getEstimatedMemoryUsage() const {
    if (residencyState == ResidencyState::ON_DISK) {
        return 0;
    }
    uint64_t memoryUsage = 0;
    for (const auto& chunk : chunks) {
        memoryUsage += chunk->getEstimatedMemoryUsage();
    }
    return memoryUsage;
}

bool ChunkedNodeGroup::hasUpdates() const {
    for (const auto& chunk : chunks) {
        if (chunk->hasUpdates()) {
            return true;
        }
    }
    return false;
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
void ChunkedNodeGroup::commitInsert(row_idx_t startRow, row_idx_t numRowsToCommit,
    transaction_t commitTS) {
    versionInfo->commitInsert(startRow, numRowsToCommit, commitTS);
}

void ChunkedNodeGroup::rollbackInsert(row_idx_t startRow, row_idx_t numRows_, transaction_t) {
    if (startRow == 0) {
        truncate(0);
        versionInfo.reset();
        return;
    }
    if (startRow >= numRows) {
        // Nothing to rollback.
        return;
    }
    versionInfo->rollbackInsert(startRow, numRows_);
    numRows = startRow;
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
void ChunkedNodeGroup::commitDelete(row_idx_t startRow, row_idx_t numRows_,
    transaction_t commitTS) {
    versionInfo->commitDelete(startRow, numRows_, commitTS);
}

// NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
void ChunkedNodeGroup::rollbackDelete(row_idx_t startRow, row_idx_t numRows_, transaction_t) {
    versionInfo->rollbackDelete(startRow, numRows_);
}

void ChunkedNodeGroup::reclaimStorage(PageAllocator& pageAllocator) const {
    for (auto& columnChunk : chunks) {
        if (columnChunk) {
            columnChunk->reclaimStorage(pageAllocator);
        }
    }
}

void ChunkedNodeGroup::serialize(Serializer& serializer) const {
    KU_ASSERT(residencyState == ResidencyState::ON_DISK);
    serializer.writeDebuggingInfo("chunks");
    serializer.serializeVectorOfPtrs(chunks);
    serializer.writeDebuggingInfo("startRowIdx");
    serializer.write(startRowIdx);
    serializer.writeDebuggingInfo("has_version_info");
    serializer.write<bool>(versionInfo != nullptr);
    if (versionInfo) {
        serializer.writeDebuggingInfo("version_info");
        versionInfo->serialize(serializer);
    }
}

std::unique_ptr<ChunkedNodeGroup> ChunkedNodeGroup::deserialize(MemoryManager& memoryManager,
    Deserializer& deSer) {
    std::string key;
    std::vector<std::unique_ptr<ColumnChunk>> chunks;
    bool hasVersions = false;
    row_idx_t startRowIdx = 0;
    deSer.validateDebuggingInfo(key, "chunks");
    deSer.deserializeVectorOfPtrs<ColumnChunk>(chunks,
        [&](Deserializer& deser) { return ColumnChunk::deserialize(memoryManager, deser); });
    deSer.validateDebuggingInfo(key, "startRowIdx");
    deSer.deserializeValue<row_idx_t>(startRowIdx);
    auto chunkedGroup = std::make_unique<ChunkedNodeGroup>(std::move(chunks), startRowIdx);
    deSer.validateDebuggingInfo(key, "has_version_info");
    deSer.deserializeValue<bool>(hasVersions);
    if (hasVersions) {
        deSer.validateDebuggingInfo(key, "version_info");
        chunkedGroup->versionInfo = VersionInfo::deserialize(deSer);
    }
    return chunkedGroup;
}

InMemChunkedNodeGroup::InMemChunkedNodeGroup(MemoryManager& mm,
    const std::vector<common::LogicalType>& columnTypes, bool enableCompression, uint64_t capacity,
    common::row_idx_t startRowIdx)
    : startRowIdx{startRowIdx}, numRows{0}, capacity{capacity}, dataInUse{true} {
    chunks.reserve(columnTypes.size());
    for (auto& type : columnTypes) {
        chunks.push_back(ColumnChunkFactory::createColumnChunkData(mm, type.copy(),
            enableCompression, capacity, ResidencyState::IN_MEMORY));
    }
}

InMemChunkedNodeGroup::InMemChunkedNodeGroup(std::vector<std::unique_ptr<ColumnChunkData>>&& chunks,
    row_idx_t startRowIdx)
    : startRowIdx{startRowIdx}, numRows{chunks[0]->getNumValues()}, capacity{numRows},
      chunks{std::move(chunks)}, dataInUse{true} {
    KU_ASSERT(!this->chunks.empty());
    for (auto columnID = 1u; columnID < this->chunks.size(); columnID++) {
        KU_ASSERT(this->chunks[columnID]->getNumValues() == numRows);
    }
}

void InMemChunkedNodeGroup::setUnused(const MemoryManager& mm) {
    dataInUse = false;
    mm.getBufferManager()->getSpillerOrSkip([&](auto& spiller) { spiller.addUnusedChunk(this); });
}

void InMemChunkedNodeGroup::loadFromDisk(const MemoryManager& mm) {
    mm.getBufferManager()->getSpillerOrSkip([&](auto& spiller) {
        std::unique_lock lock{spillToDiskMutex};
        // Prevent buffer manager from being able to spill this chunk to disk
        spiller.clearUnusedChunk(this);
        for (auto& chunk : chunks) {
            chunk->loadFromDisk();
        }
        dataInUse = true;
    });
}

SpillResult InMemChunkedNodeGroup::spillToDisk() {
    uint64_t reclaimedSpace = 0;
    uint64_t nowEvictableMemory = 0;
    std::unique_lock lock{spillToDiskMutex};
    // Its possible that the chunk may be loaded and marked as in-use between when it is selected to
    // be spilled to disk and actually spilled
    if (!dataInUse) {
        // These are groups from the partitioner which specifically are internalID columns and thus
        // don't have a null column or any other sort of child column. That being said, it may be a
        // good idea to make the interface more generic, which would open up the possibility of
        // spilling to disk during node table copies too.
        for (size_t i = 0; i < getNumColumns(); i++) {
            auto [reclaimed, nowEvictable] = getColumnChunk(i).spillToDisk();
            reclaimedSpace += reclaimed;
            nowEvictableMemory += nowEvictable;
        }
    }
    return SpillResult{reclaimedSpace, nowEvictableMemory};
}

void InMemChunkedNodeGroup::resetToEmpty() {
    numRows = 0;
    for (const auto& chunk : chunks) {
        chunk->resetToEmpty();
    }
}

void InMemChunkedNodeGroup::resetToAllNull() const {
    for (const auto& chunk : chunks) {
        chunk->resetToAllNull();
    }
}

void InMemChunkedNodeGroup::resizeChunks(const uint64_t newSize) {
    if (newSize <= capacity) {
        return;
    }
    for (auto& chunk : chunks) {
        chunk->resize(newSize);
    }
    capacity = newSize;
}

uint64_t InMemChunkedNodeGroup::append(const std::vector<ValueVector*>& columnVectors,
    row_idx_t startRowInVectors, uint64_t numValuesToAppend) {
    KU_ASSERT(columnVectors.size() == chunks.size());
    const auto numRowsToAppendInChunk = std::min(numValuesToAppend, capacity - numRows);
    try {
        for (auto i = 0u; i < columnVectors.size(); i++) {
            const auto columnVector = columnVectors[i];
            chunks[i]->append(columnVector, columnVector->state->getSelVector().slice(
                                                startRowInVectors, numRowsToAppendInChunk));
        }
    } catch ([[maybe_unused]] std::exception& e) {
        handleAppendException(chunks, numRows);
    }
    numRows += numRowsToAppendInChunk;
    return numRowsToAppendInChunk;
}

offset_t InMemChunkedNodeGroup::append(const InMemChunkedNodeGroup& other,
    offset_t offsetInOtherNodeGroup, offset_t numRowsToAppend) {
    KU_ASSERT(other.chunks.size() == chunks.size());
    const auto numToAppendInChunkedGroup = std::min(numRowsToAppend, capacity - numRows);
    try {
        for (auto i = 0u; i < other.getNumColumns(); i++) {
            chunks[i]->append(other.chunks[i].get(), offsetInOtherNodeGroup,
                numToAppendInChunkedGroup);
        }
    } catch ([[maybe_unused]] std::exception& e) {
        handleAppendException(chunks, numRows);
    }
    numRows += numToAppendInChunkedGroup;
    return numToAppendInChunkedGroup;
}

void InMemChunkedNodeGroup::merge(InMemChunkedNodeGroup& base,
    const std::vector<column_id_t>& columnsToMergeInto) {
    KU_ASSERT(base.getNumColumns() == columnsToMergeInto.size());
    for (idx_t i = 0; i < base.getNumColumns(); ++i) {
        KU_ASSERT(columnsToMergeInto[i] < chunks.size());
        chunks[columnsToMergeInto[i]] = base.moveColumnChunk(i);
    }
}

InMemChunkedNodeGroup::InMemChunkedNodeGroup(InMemChunkedNodeGroup& base,
    const std::vector<column_id_t>& selectedColumns)
    : startRowIdx{base.getStartRowIdx()}, numRows{base.getNumRows()}, capacity{base.getCapacity()},
      dataInUse{true} {
    chunks.resize(selectedColumns.size());
    for (auto i = 0u; i < selectedColumns.size(); i++) {
        auto columnID = selectedColumns[i];
        KU_ASSERT(columnID < base.getNumColumns());
        chunks[i] = base.moveColumnChunk(columnID);
    }
}

} // namespace storage
} // namespace lbug
