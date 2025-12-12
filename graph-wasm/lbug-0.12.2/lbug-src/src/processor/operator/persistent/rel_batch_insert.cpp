#include "processor/operator/persistent/rel_batch_insert.h"

#include "catalog/catalog.h"
#include "common/cast.h"
#include "common/exception/copy.h"
#include "common/exception/message.h"
#include "common/string_format.h"
#include "common/task_system/progress_bar.h"
#include "processor/execution_context.h"
#include "processor/result/factorized_table_util.h"
#include "processor/warning_context.h"
#include "storage/local_storage/local_storage.h"
#include "storage/storage_manager.h"
#include "storage/storage_utils.h"
#include "storage/table/chunked_node_group.h"
#include "storage/table/column_chunk_data.h"
#include "storage/table/csr_chunked_node_group.h"
#include "storage/table/rel_table.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::string RelBatchInsertPrintInfo::toString() const {
    std::string result = "Table Name: ";
    result += tableName;
    return result;
}

void RelBatchInsert::initLocalStateInternal(ResultSet*, ExecutionContext* context) {
    localState = std::make_unique<RelBatchInsertLocalState>();
    const auto relInfo = info->ptrCast<RelBatchInsertInfo>();
    localState->chunkedGroup =
        std::make_unique<InMemChunkedCSRNodeGroup>(*MemoryManager::Get(*context->clientContext),
            relInfo->columnTypes, relInfo->compressionEnabled, 0, 0);
    const auto transaction = transaction::Transaction::Get(*context->clientContext);
    localState->optimisticAllocator = transaction->getLocalStorage()->addOptimisticAllocator();
    const auto clientContext = context->clientContext;
    const auto catalog = Catalog::Get(*clientContext);
    const auto catalogEntry = catalog->getTableCatalogEntry(transaction, info->tableName);
    const auto& relGroupEntry = catalogEntry->constCast<RelGroupCatalogEntry>();
    auto tableID = relGroupEntry.getRelEntryInfo(relInfo->fromTableID, relInfo->toTableID)->oid;
    auto nbrTableID = RelDirectionUtils::getNbrTableID(relInfo->direction, relInfo->fromTableID,
        relInfo->toTableID);
    // TODO(Guodong): Get rid of the hard-coded nbr and rel column ID 0/1.
    localState->chunkedGroup->getColumnChunk(0).cast<InternalIDChunkData>().setTableID(nbrTableID);
    localState->chunkedGroup->getColumnChunk(1).cast<InternalIDChunkData>().setTableID(tableID);
    const auto relLocalState = localState->ptrCast<RelBatchInsertLocalState>();
    relLocalState->dummyAllNullDataChunk = std::make_unique<DataChunk>(relInfo->columnTypes.size());
    for (auto i = 0u; i < relInfo->columnTypes.size(); i++) {
        auto valueVector = std::make_shared<ValueVector>(relInfo->columnTypes[i].copy(),
            MemoryManager::Get(*context->clientContext));
        valueVector->setAllNull();
        relLocalState->dummyAllNullDataChunk->insert(i, std::move(valueVector));
    }
}

void RelBatchInsert::initGlobalStateInternal(ExecutionContext* context) {
    const auto relBatchInsertInfo = info->ptrCast<RelBatchInsertInfo>();
    const auto clientContext = context->clientContext;
    const auto catalog = Catalog::Get(*clientContext);
    const auto transaction = transaction::Transaction::Get(*clientContext);
    const auto catalogEntry = catalog->getTableCatalogEntry(transaction, info->tableName);
    const auto& relGroupEntry = catalogEntry->constCast<RelGroupCatalogEntry>();
    // Init info
    info->compressionEnabled = StorageManager::Get(*clientContext)->compressionEnabled();
    auto dataColumnIdx = 0u;
    // Handle internal id column
    info->columnTypes.push_back(LogicalType::INTERNAL_ID());
    info->insertColumnIDs.push_back(0);
    info->outputDataColumns.push_back(dataColumnIdx++);
    for (auto& property : relGroupEntry.getProperties()) {
        info->columnTypes.push_back(property.getType().copy());
        info->insertColumnIDs.push_back(relGroupEntry.getColumnID(property.getName()));
        info->outputDataColumns.push_back(dataColumnIdx++);
    }
    for (auto& type : info->warningColumnTypes) {
        info->columnTypes.push_back(type.copy());
        info->warningDataColumns.push_back(dataColumnIdx++);
    }
    relBatchInsertInfo->partitioningIdx =
        relBatchInsertInfo->direction == RelDataDirection::FWD ? 0 : 1;
    relBatchInsertInfo->boundNodeOffsetColumnID =
        relBatchInsertInfo->direction == RelDataDirection::FWD ? 0 : 1;
    // Init shared state
    sharedState->table = partitionerSharedState->relTable;
    progressSharedState = std::make_shared<RelBatchInsertProgressSharedState>();
    progressSharedState->partitionsDone = 0;
    progressSharedState->partitionsTotal =
        partitionerSharedState->getNumPartitions(relBatchInsertInfo->partitioningIdx);
}

void RelBatchInsert::executeInternal(ExecutionContext* context) {
    const auto relInfo = info->ptrCast<RelBatchInsertInfo>();
    const auto relTable = sharedState->table->ptrCast<RelTable>();
    const auto relLocalState = localState->ptrCast<RelBatchInsertLocalState>();
    const auto clientContext = context->clientContext;
    const auto catalog = Catalog::Get(*clientContext);
    const auto transaction = transaction::Transaction::Get(*clientContext);
    const auto& relGroupEntry = catalog->getTableCatalogEntry(transaction, relInfo->tableName)
                                    ->constCast<RelGroupCatalogEntry>();
    while (true) {
        relLocalState->nodeGroupIdx =
            partitionerSharedState->getNextPartition(relInfo->partitioningIdx);
        if (relLocalState->nodeGroupIdx == INVALID_PARTITION_IDX) {
            // No more partitions left in the partitioning buffer.
            break;
        }
        ++progressSharedState->partitionsDone;
        // TODO(Guodong): We need to handle the concurrency between COPY and other insertions
        // into the same node group.
        auto& nodeGroup =
            relTable
                ->getOrCreateNodeGroup(transaction, relLocalState->nodeGroupIdx, relInfo->direction)
                ->cast<CSRNodeGroup>();
        appendNodeGroup(relGroupEntry, *MemoryManager::Get(*clientContext), transaction, nodeGroup,
            *relInfo, *relLocalState);
        updateProgress(context);
    }
}

static void appendNewChunkedGroup(MemoryManager& mm, transaction::Transaction* transaction,
    const std::vector<column_id_t>& columnIDs, InMemChunkedCSRNodeGroup& chunkedGroup,
    RelTable& relTable, CSRNodeGroup& nodeGroup, RelDataDirection direction,
    PageAllocator& pageAllocator) {
    const bool isNewNodeGroup = nodeGroup.isEmpty();
    const CSRNodeGroupScanSource source = isNewNodeGroup ?
                                              CSRNodeGroupScanSource::COMMITTED_PERSISTENT :
                                              CSRNodeGroupScanSource::COMMITTED_IN_MEMORY;
    // since each thread operates on distinct node groups
    // We don't need a lock here (to ensure the insert info and append agree on the number of rows
    // in the node group)
    relTable.pushInsertInfo(transaction, direction, nodeGroup, chunkedGroup.getNumRows(), source);
    if (isNewNodeGroup) {
        auto flushedChunkedGroup = chunkedGroup.flush(transaction, pageAllocator);

        // If there are deleted columns that haven't been vacuumed yet
        // we need to add extra columns to the chunked group
        // to ensure that the number of columns is consistent with the rest of the node group
        auto persistentChunkedGroup = std::make_unique<ChunkedCSRNodeGroup>(mm,
            flushedChunkedGroup->cast<ChunkedCSRNodeGroup>(), nodeGroup.getDataTypes(), columnIDs);

        nodeGroup.setPersistentChunkedGroup(std::move(persistentChunkedGroup));
    } else {
        nodeGroup.appendChunkedCSRGroup(transaction, columnIDs, chunkedGroup);
    }
}

void RelBatchInsert::appendNodeGroup(const RelGroupCatalogEntry& relGroupEntry, MemoryManager& mm,
    transaction::Transaction* transaction, CSRNodeGroup& nodeGroup,
    const RelBatchInsertInfo& relInfo, const RelBatchInsertLocalState& localState) {
    const auto nodeGroupIdx = localState.nodeGroupIdx;
    const auto startNodeOffset = storage::StorageUtils::getStartOffsetOfNodeGroup(nodeGroupIdx);
    auto executionState = impl->initExecutionState(*partitionerSharedState, relInfo, nodeGroupIdx);
    // Calculate num of source nodes in this node group.
    // This will be used to set the num of values of the node group.
    const auto numNodes = std::min(StorageConfig::NODE_GROUP_SIZE,
        partitionerSharedState->getNumNodes(relInfo.partitioningIdx) - startNodeOffset);
    // We optimistically flush new node group directly to disk in gapped CSR format.
    // There is no benefit of leaving gaps for existing node groups, which is kept in memory.
    const auto leaveGaps = nodeGroup.isEmpty();
    populateCSRHeader(relGroupEntry, *executionState, startNodeOffset, relInfo, localState,
        numNodes, leaveGaps);
    const auto& csrHeader =
        ku_dynamic_cast<InMemChunkedCSRNodeGroup&>(*localState.chunkedGroup).getCSRHeader();
    impl->writeToTable(*executionState, csrHeader, localState, *sharedState, relInfo);
    // Reset num of rows in the chunked group to fill gaps at the end of the node group.
    const auto maxSize = csrHeader.getEndCSROffset(numNodes - 1);
    auto numGapsAtEnd = maxSize - localState.chunkedGroup->getNumRows();
    KU_ASSERT(localState.chunkedGroup->getCapacity() >= maxSize);
    while (numGapsAtEnd > 0) {
        const auto numGapsToFill = std::min(numGapsAtEnd, DEFAULT_VECTOR_CAPACITY);
        localState.dummyAllNullDataChunk->state->getSelVectorUnsafe().setSelSize(numGapsToFill);
        std::vector<ValueVector*> dummyVectors;
        for (auto i = 0u; i < relInfo.columnTypes.size(); i++) {
            dummyVectors.push_back(&localState.dummyAllNullDataChunk->getValueVectorMutable(i));
        }
        const auto numGapsFilled = localState.chunkedGroup->append(dummyVectors, 0, numGapsToFill);
        KU_ASSERT(numGapsFilled == numGapsToFill);
        numGapsAtEnd -= numGapsFilled;
    }
    KU_ASSERT(localState.chunkedGroup->getNumRows() == maxSize);

    auto* relTable = sharedState->table->ptrCast<RelTable>();

    InMemChunkedCSRNodeGroup sliceToWriteToDisk{
        ku_dynamic_cast<InMemChunkedCSRNodeGroup&>(*localState.chunkedGroup),
        relInfo.outputDataColumns};
    appendNewChunkedGroup(mm, transaction, relInfo.insertColumnIDs, sliceToWriteToDisk, *relTable,
        nodeGroup, relInfo.direction, *localState.optimisticAllocator);
    ku_dynamic_cast<InMemChunkedCSRNodeGroup&>(*localState.chunkedGroup)
        .mergeChunkedCSRGroup(sliceToWriteToDisk, relInfo.outputDataColumns);

    localState.chunkedGroup->resetToEmpty();
}

void RelBatchInsertImpl::finalizeStartCSROffsets(RelBatchInsertExecutionState&,
    storage::InMemChunkedCSRHeader& csrHeader, const RelBatchInsertInfo&) {
    csrHeader.populateEndCSROffsetFromStartAndLength();
}

void RelBatchInsert::populateCSRHeader(const RelGroupCatalogEntry& relGroupEntry,
    RelBatchInsertExecutionState& executionState, offset_t startNodeOffset,
    const RelBatchInsertInfo& relInfo, const RelBatchInsertLocalState& localState,
    offset_t numNodes, bool leaveGaps) {
    auto& csrNodeGroup = ku_dynamic_cast<InMemChunkedCSRNodeGroup&>(*localState.chunkedGroup);
    auto& csrHeader = csrNodeGroup.getCSRHeader();
    csrHeader.setNumValues(numNodes);
    // Populate lengths for each node and check multiplicity constraint.
    impl->populateCSRLengths(executionState, csrHeader, numNodes, relInfo);
    checkRelMultiplicityConstraint(relGroupEntry, csrHeader, startNodeOffset, relInfo);
    const auto rightCSROffsetOfRegions = csrHeader.populateStartCSROffsetsFromLength(leaveGaps);
    impl->finalizeStartCSROffsets(executionState, csrHeader, relInfo);
    csrHeader.finalizeCSRRegionEndOffsets(rightCSROffsetOfRegions);
    // Resize csr data column chunks.
    localState.chunkedGroup->resizeChunks(csrHeader.getEndCSROffset(numNodes - 1));
    localState.chunkedGroup->resetToAllNull();
    KU_ASSERT(csrHeader.sanityCheck());
}

void RelBatchInsert::checkRelMultiplicityConstraint(const RelGroupCatalogEntry& relGroupEntry,
    const InMemChunkedCSRHeader& csrHeader, offset_t startNodeOffset,
    const RelBatchInsertInfo& relInfo) {
    if (!relGroupEntry.isSingleMultiplicity(relInfo.direction)) {
        return;
    }
    for (auto i = 0u; i < csrHeader.length->getNumValues(); i++) {
        if (csrHeader.length->getValue<length_t>(i) > 1) {
            throw CopyException(ExceptionMessage::violateRelMultiplicityConstraint(
                relInfo.tableName, std::to_string(i + startNodeOffset),
                RelDirectionUtils::relDirectionToString(relInfo.direction)));
        }
    }
}

void RelBatchInsert::finalizeInternal(ExecutionContext* context) {
    const auto relInfo = info->ptrCast<RelBatchInsertInfo>();
    if (relInfo->direction == RelDataDirection::FWD) {
        KU_ASSERT(relInfo->partitioningIdx == 0);

        auto outputMsg = stringFormat("{} tuples have been copied to the {} table.",
            sharedState->getNumRows(), relInfo->tableName);
        auto clientContext = context->clientContext;
        FactorizedTableUtils::appendStringToTable(sharedState->fTable.get(), outputMsg,
            MemoryManager::Get(*clientContext));

        auto warningContext = WarningContext::Get(*context->clientContext);
        const auto warningCount = warningContext->getWarningCount(context->queryID);
        if (warningCount > 0) {
            auto warningMsg =
                stringFormat("{} warnings encountered during copy. Use 'CALL "
                             "show_warnings() RETURN *' to view the actual warnings. Query ID: {}",
                    warningCount, context->queryID);
            FactorizedTableUtils::appendStringToTable(sharedState->fTable.get(), warningMsg,
                MemoryManager::Get(*context->clientContext));
            warningContext->defaultPopulateAllWarnings(context->queryID);
        }
    }
    sharedState->numRows.store(0);
    sharedState->table->cast<RelTable>().setHasChanges();
    partitionerSharedState->resetState(relInfo->partitioningIdx);
}

void RelBatchInsert::updateProgress(const ExecutionContext* context) const {
    auto progressBar = ProgressBar::Get(*context->clientContext);
    if (progressSharedState->partitionsTotal == 0) {
        progressBar->updateProgress(context->queryID, 0);
    } else {
        double progress = static_cast<double>(progressSharedState->partitionsDone) /
                          static_cast<double>(progressSharedState->partitionsTotal);
        progressBar->updateProgress(context->queryID, progress);
    }
}

} // namespace processor
} // namespace lbug
