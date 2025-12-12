#include "processor/operator/persistent/copy_rel_batch_insert.h"

#include "storage/storage_utils.h"
#include "storage/table/csr_chunked_node_group.h"

namespace lbug {
namespace processor {

static void setOffsetToWithinNodeGroup(storage::ColumnChunkData& chunk,
    common::offset_t startOffset) {
    KU_ASSERT(chunk.getDataType().getPhysicalType() == common::PhysicalTypeID::INTERNAL_ID);
    const auto offsets = reinterpret_cast<common::offset_t*>(chunk.getData());
    for (auto i = 0u; i < chunk.getNumValues(); i++) {
        offsets[i] -= startOffset;
    }
}

std::unique_ptr<RelBatchInsertExecutionState> CopyRelBatchInsert::initExecutionState(
    const PartitionerSharedState& partitionerSharedState, const RelBatchInsertInfo& relInfo,
    common::node_group_idx_t nodeGroupIdx) {
    auto executionState = std::make_unique<CopyRelBatchInsertExecutionState>();
    executionState->partitioningBuffer =
        partitionerSharedState.constCast<CopyPartitionerSharedState>().getPartitionBuffer(
            relInfo.partitioningIdx, nodeGroupIdx);
    const auto startNodeOffset = storage::StorageUtils::getStartOffsetOfNodeGroup(nodeGroupIdx);
    for (auto& chunkedGroup : executionState->partitioningBuffer->getChunkedGroups()) {
        setOffsetToWithinNodeGroup(chunkedGroup->getColumnChunk(relInfo.boundNodeOffsetColumnID),
            startNodeOffset);
    }
    return executionState;
}

void CopyRelBatchInsert::populateCSRLengthsInternal(const storage::InMemChunkedCSRHeader& csrHeader,
    common::offset_t numNodes, storage::InMemChunkedNodeGroupCollection& partition,
    common::column_id_t boundNodeOffsetColumn) {
    KU_ASSERT(numNodes == csrHeader.length->getNumValues() &&
              numNodes == csrHeader.offset->getNumValues());
    const auto lengthData = reinterpret_cast<common::length_t*>(csrHeader.length->getData());
    std::fill(lengthData, lengthData + numNodes, 0);
    for (auto& chunkedGroup : partition.getChunkedGroups()) {
        auto& offsetChunk = chunkedGroup->getColumnChunk(boundNodeOffsetColumn);
        for (auto i = 0u; i < offsetChunk.getNumValues(); i++) {
            const auto nodeOffset = offsetChunk.getValue<common::offset_t>(i);
            KU_ASSERT(nodeOffset < numNodes);
            lengthData[nodeOffset]++;
        }
    }
}

void CopyRelBatchInsert::populateCSRLengths(RelBatchInsertExecutionState& executionState,
    storage::InMemChunkedCSRHeader& csrHeader, common::offset_t numNodes,
    const RelBatchInsertInfo& relInfo) {
    auto& copyRelExecutionState = executionState.cast<CopyRelBatchInsertExecutionState>();
    populateCSRLengthsInternal(csrHeader, numNodes, *copyRelExecutionState.partitioningBuffer,
        relInfo.boundNodeOffsetColumnID);
}

void CopyRelBatchInsert::setRowIdxFromCSROffsets(storage::ColumnChunkData& rowIdxChunk,
    storage::ColumnChunkData& csrOffsetChunk) {
    KU_ASSERT(rowIdxChunk.getDataType().getPhysicalType() == common::PhysicalTypeID::INTERNAL_ID);
    for (auto i = 0u; i < rowIdxChunk.getNumValues(); i++) {
        const auto nodeOffset = rowIdxChunk.getValue<common::offset_t>(i);
        const auto csrOffset = csrOffsetChunk.getValue<common::offset_t>(nodeOffset);
        rowIdxChunk.setValue<common::offset_t>(csrOffset, i);
        // Increment current csr offset for nodeOffset by 1.
        csrOffsetChunk.setValue<common::offset_t>(csrOffset + 1, nodeOffset);
    }
}

void CopyRelBatchInsert::finalizeStartCSROffsets(RelBatchInsertExecutionState& executionState,
    storage::InMemChunkedCSRHeader& csrHeader, const RelBatchInsertInfo& relInfo) {
    auto& copyRelExecutionState = executionState.cast<CopyRelBatchInsertExecutionState>();
    for (auto& chunkedGroup : copyRelExecutionState.partitioningBuffer->getChunkedGroups()) {
        auto& offsetChunk = chunkedGroup->getColumnChunk(relInfo.boundNodeOffsetColumnID);
        // We reuse bound node offset column to store row idx for each rel in the node group.
        setRowIdxFromCSROffsets(offsetChunk, *csrHeader.offset);
    }
}

void CopyRelBatchInsert::writeToTable(RelBatchInsertExecutionState& executionState,
    const storage::InMemChunkedCSRHeader&, const RelBatchInsertLocalState& localState,
    BatchInsertSharedState& sharedState, const RelBatchInsertInfo& relInfo) {
    auto& copyRelExecutionState = executionState.cast<CopyRelBatchInsertExecutionState>();
    for (auto& chunkedGroup : copyRelExecutionState.partitioningBuffer->getChunkedGroups()) {
        sharedState.incrementNumRows(chunkedGroup->getNumRows());
        // we reused the bound node offset column to store row idx
        // the row idx column determines which rows to write each entry in the chunked group to
        localState.chunkedGroup->write(*chunkedGroup, relInfo.boundNodeOffsetColumnID);
    }
}
} // namespace processor
} // namespace lbug
