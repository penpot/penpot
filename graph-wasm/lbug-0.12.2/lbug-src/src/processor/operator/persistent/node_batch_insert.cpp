#include "processor/operator/persistent/node_batch_insert.h"

#include "catalog/catalog_entry/node_table_catalog_entry.h"
#include "common/cast.h"
#include "common/finally_wrapper.h"
#include "common/string_format.h"
#include "processor/execution_context.h"
#include "processor/operator/persistent/index_builder.h"
#include "processor/result/factorized_table_util.h"
#include "processor/warning_context.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/local_storage/local_storage.h"
#include "storage/storage_manager.h"
#include "storage/table/chunked_node_group.h"
#include "storage/table/node_table.h"
#include "transaction/transaction.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::transaction;

namespace lbug {
namespace processor {

std::string NodeBatchInsertPrintInfo::toString() const {
    std::string result = "Table Name: ";
    result += tableName;
    return result;
}

void NodeBatchInsertSharedState::initPKIndex(const ExecutionContext* context) {
    uint64_t numRows = 0;
    if (tableFuncSharedState != nullptr) {
        numRows = tableFuncSharedState->getNumRows();
    }
    auto* nodeTable = ku_dynamic_cast<NodeTable*>(table);
    nodeTable->getPKIndex()->bulkReserve(numRows);
    globalIndexBuilder = IndexBuilder(std::make_shared<IndexBuilderSharedState>(
        Transaction::Get(*context->clientContext), nodeTable));
}

void NodeBatchInsert::initGlobalStateInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto catalog = Catalog::Get(*clientContext);
    auto transaction = Transaction::Get(*clientContext);
    auto nodeTableEntry = catalog->getTableCatalogEntry(transaction, info->tableName)
                              ->ptrCast<NodeTableCatalogEntry>();
    auto nodeTable = StorageManager::Get(*clientContext)->getTable(nodeTableEntry->getTableID());
    const auto& pkDefinition = nodeTableEntry->getPrimaryKeyDefinition();
    auto pkColumnID = nodeTableEntry->getColumnID(pkDefinition.getName());
    // Init info
    info->compressionEnabled = StorageManager::Get(*clientContext)->compressionEnabled();
    auto dataColumnIdx = 0u;
    for (auto& property : nodeTableEntry->getProperties()) {
        info->columnTypes.push_back(property.getType().copy());
        info->insertColumnIDs.push_back(nodeTableEntry->getColumnID(property.getName()));
        info->outputDataColumns.push_back(dataColumnIdx++);
    }
    for (auto& type : info->warningColumnTypes) {
        info->columnTypes.push_back(type.copy());
        info->warningDataColumns.push_back(dataColumnIdx++);
    }
    // Init shared state
    auto nodeSharedState = sharedState->ptrCast<NodeBatchInsertSharedState>();
    nodeSharedState->table = nodeTable;
    nodeSharedState->pkColumnID = pkColumnID;
    nodeSharedState->pkType = pkDefinition.getType().copy();
    nodeSharedState->initPKIndex(context);
}

void NodeBatchInsert::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) {
    const auto nodeInfo = info->ptrCast<NodeBatchInsertInfo>();
    const auto numColumns = nodeInfo->columnEvaluators.size();

    const auto nodeSharedState = ku_dynamic_cast<NodeBatchInsertSharedState*>(sharedState.get());
    localState = std::make_unique<NodeBatchInsertLocalState>(
        std::span{nodeInfo->columnTypes.begin(), nodeInfo->outputDataColumns.size()});
    const auto nodeLocalState = localState->ptrCast<NodeBatchInsertLocalState>();
    KU_ASSERT(nodeSharedState->globalIndexBuilder);
    nodeLocalState->localIndexBuilder = nodeSharedState->globalIndexBuilder->clone();
    nodeLocalState->errorHandler = createErrorHandler(context);
    nodeLocalState->optimisticAllocator =
        Transaction::Get(*context->clientContext)->getLocalStorage()->addOptimisticAllocator();

    nodeLocalState->columnVectors.resize(numColumns);

    for (auto i = 0u; i < numColumns; ++i) {
        auto& evaluator = nodeInfo->columnEvaluators[i];
        evaluator->init(*resultSet, context->clientContext);
        nodeLocalState->columnVectors[i] = evaluator->resultVector.get();
    }
    nodeLocalState->chunkedGroup =
        std::make_unique<InMemChunkedNodeGroup>(*MemoryManager::Get(*context->clientContext),
            nodeInfo->columnTypes, info->compressionEnabled, StorageConfig::NODE_GROUP_SIZE, 0);
    KU_ASSERT(resultSet->dataChunks[0]);
    nodeLocalState->columnState = resultSet->dataChunks[0]->state;
}

void NodeBatchInsert::executeInternal(ExecutionContext* context) {
    const auto clientContext = context->clientContext;
    std::optional<ProducerToken> token;
    auto nodeLocalState = localState->ptrCast<NodeBatchInsertLocalState>();
    if (nodeLocalState->localIndexBuilder) {
        token = nodeLocalState->localIndexBuilder->getProducerToken();
    }
    auto transaction = Transaction::Get(*clientContext);
    while (children[0]->getNextTuple(context)) {
        const auto originalSelVector = nodeLocalState->columnState->getSelVectorShared();
        // Evaluate expressions if needed.
        const auto numTuples = nodeLocalState->columnState->getSelVector().getSelSize();
        evaluateExpressions(numTuples);
        copyToNodeGroup(transaction, MemoryManager::Get(*clientContext)),
            nodeLocalState->columnState->setSelVector(originalSelVector);
    }
    if (nodeLocalState->chunkedGroup->getNumRows() > 0) {
        appendIncompleteNodeGroup(transaction, std::move(nodeLocalState->chunkedGroup),
            nodeLocalState->localIndexBuilder, MemoryManager::Get(*context->clientContext));
    }
    if (nodeLocalState->localIndexBuilder) {
        KU_ASSERT(token);
        token->quit();

        KU_ASSERT(nodeLocalState->errorHandler.has_value());
        nodeLocalState->localIndexBuilder->finishedProducing(nodeLocalState->errorHandler.value());
        nodeLocalState->errorHandler->flushStoredErrors();
    }
    const auto nodeInfo = info->ptrCast<NodeBatchInsertInfo>();
    sharedState->table->cast<NodeTable>().mergeStats(nodeInfo->insertColumnIDs,
        nodeLocalState->stats);
}

void NodeBatchInsert::evaluateExpressions(uint64_t numTuples) const {
    const auto nodeInfo = info->ptrCast<NodeBatchInsertInfo>();
    for (auto i = 0u; i < nodeInfo->evaluateTypes.size(); ++i) {
        switch (nodeInfo->evaluateTypes[i]) {
        case ColumnEvaluateType::DEFAULT: {
            nodeInfo->columnEvaluators[i]->evaluate(numTuples);
        } break;
        case ColumnEvaluateType::CAST: {
            nodeInfo->columnEvaluators[i]->evaluate();
        } break;
        default:
            break;
        }
    }
}

void NodeBatchInsert::copyToNodeGroup(transaction::Transaction* transaction,
    MemoryManager* mm) const {
    auto numAppendedTuples = 0ul;
    const auto nodeLocalState = ku_dynamic_cast<NodeBatchInsertLocalState*>(localState.get());
    const auto numTuplesToAppend = nodeLocalState->columnState->getSelVector().getSelSize();
    while (numAppendedTuples < numTuplesToAppend) {
        const auto numAppendedTuplesInNodeGroup =
            nodeLocalState->chunkedGroup->append(nodeLocalState->columnVectors, numAppendedTuples,
                numTuplesToAppend - numAppendedTuples);
        numAppendedTuples += numAppendedTuplesInNodeGroup;
        if (nodeLocalState->chunkedGroup->isFull()) {
            writeAndResetNodeGroup(transaction, nodeLocalState->chunkedGroup,
                nodeLocalState->localIndexBuilder, mm, *nodeLocalState->optimisticAllocator);
        }
    }
    const auto nodeInfo = info->ptrCast<NodeBatchInsertInfo>();
    nodeLocalState->stats.update(nodeLocalState->columnVectors, nodeInfo->outputDataColumns.size());
    sharedState->incrementNumRows(numAppendedTuples);
}

NodeBatchInsertErrorHandler NodeBatchInsert::createErrorHandler(ExecutionContext* context) const {
    const auto nodeSharedState = ku_dynamic_cast<NodeBatchInsertSharedState*>(sharedState.get());
    auto* nodeTable = ku_dynamic_cast<NodeTable*>(sharedState->table);
    return NodeBatchInsertErrorHandler{context, nodeSharedState->pkType.getLogicalTypeID(),
        nodeTable, WarningContext::Get(*context->clientContext)->getIgnoreErrorsOption(),
        sharedState->numErroredRows, &sharedState->erroredRowMutex};
}

void NodeBatchInsert::clearToIndex(MemoryManager* mm,
    std::unique_ptr<InMemChunkedNodeGroup>& nodeGroup, offset_t startIndexInGroup) const {
    // Create a new chunked node group and move the unwritten values to it
    // TODO(bmwinger): Can probably re-use the chunk and shift the values
    const auto oldNodeGroup = std::move(nodeGroup);
    const auto nodeInfo = info->ptrCast<NodeBatchInsertInfo>();
    nodeGroup = std::make_unique<InMemChunkedNodeGroup>(*mm, nodeInfo->columnTypes,
        nodeInfo->compressionEnabled, StorageConfig::NODE_GROUP_SIZE, 0);
    nodeGroup->append(*oldNodeGroup, startIndexInGroup,
        oldNodeGroup->getNumRows() - startIndexInGroup);
}

void NodeBatchInsert::writeAndResetNodeGroup(transaction::Transaction* transaction,
    std::unique_ptr<InMemChunkedNodeGroup>& nodeGroup, std::optional<IndexBuilder>& indexBuilder,
    MemoryManager* mm, PageAllocator& pageAllocator) const {
    const auto nodeLocalState = localState->ptrCast<NodeBatchInsertLocalState>();
    KU_ASSERT(nodeLocalState->errorHandler.has_value());
    writeAndResetNodeGroup(transaction, nodeGroup, indexBuilder, mm,
        nodeLocalState->errorHandler.value(), pageAllocator);
}

void NodeBatchInsert::writeAndResetNodeGroup(transaction::Transaction* transaction,
    std::unique_ptr<InMemChunkedNodeGroup>& nodeGroup, std::optional<IndexBuilder>& indexBuilder,
    MemoryManager* mm, NodeBatchInsertErrorHandler& errorHandler,
    PageAllocator& pageAllocator) const {
    const auto nodeSharedState = ku_dynamic_cast<NodeBatchInsertSharedState*>(sharedState.get());
    const auto nodeTable = ku_dynamic_cast<NodeTable*>(sharedState->table);

    uint64_t nodeOffset{};
    uint64_t numRowsWritten{};
    {
        // The chunked group in batch insert may contain extra data to populate error messages
        // When we append to the table we only want the main data so this class is used to slice the
        // original chunked group
        // The slice must be restored even if an exception is thrown to prevent other threads from
        // reading invalid data
        InMemChunkedNodeGroup sliceToWriteToDisk{*nodeGroup, info->outputDataColumns};
        FinallyWrapper sliceRestorer{
            [&]() { nodeGroup->merge(sliceToWriteToDisk, info->outputDataColumns); }};
        std::tie(nodeOffset, numRowsWritten) = nodeTable->appendToLastNodeGroup(transaction,
            info->insertColumnIDs, sliceToWriteToDisk, pageAllocator);
    }

    if (indexBuilder) {
        std::vector<ColumnChunkData*> warningChunkData;
        for (const auto warningDataColumn : info->warningDataColumns) {
            warningChunkData.push_back(&nodeGroup->getColumnChunk(warningDataColumn));
        }
        indexBuilder->insert(nodeGroup->getColumnChunk(nodeSharedState->pkColumnID),
            warningChunkData, nodeOffset, numRowsWritten, errorHandler);
    }
    if (numRowsWritten == nodeGroup->getNumRows()) {
        nodeGroup->resetToEmpty();
    } else {
        clearToIndex(mm, nodeGroup, numRowsWritten);
    }
}

void NodeBatchInsert::appendIncompleteNodeGroup(transaction::Transaction* transaction,
    std::unique_ptr<InMemChunkedNodeGroup> localNodeGroup,
    std::optional<IndexBuilder>& indexBuilder, MemoryManager* mm) const {
    std::unique_lock xLck{sharedState->mtx};
    const auto nodeLocalState = ku_dynamic_cast<NodeBatchInsertLocalState*>(localState.get());
    const auto nodeSharedState = ku_dynamic_cast<NodeBatchInsertSharedState*>(sharedState.get());
    if (!nodeSharedState->sharedNodeGroup) {
        nodeSharedState->sharedNodeGroup = std::move(localNodeGroup);
        return;
    }
    uint64_t numNodesAppended = 0;
    while (numNodesAppended < localNodeGroup->getNumRows()) {
        if (nodeSharedState->sharedNodeGroup->isFull()) {
            writeAndResetNodeGroup(transaction, nodeSharedState->sharedNodeGroup, indexBuilder, mm,
                *nodeLocalState->optimisticAllocator);
        }
        numNodesAppended += nodeSharedState->sharedNodeGroup->append(*localNodeGroup,
            numNodesAppended /* offsetInNodeGroup */,
            localNodeGroup->getNumRows() - numNodesAppended);
    }
    KU_ASSERT(numNodesAppended == localNodeGroup->getNumRows());
}

void NodeBatchInsert::finalize(ExecutionContext* context) {
    KU_ASSERT(localState == nullptr);
    const auto nodeSharedState = ku_dynamic_cast<NodeBatchInsertSharedState*>(sharedState.get());
    auto errorHandler = createErrorHandler(context);
    auto clientContext = context->clientContext;
    auto transaction = Transaction::Get(*clientContext);
    auto& pageAllocator = *transaction->getLocalStorage()->addOptimisticAllocator();
    if (nodeSharedState->sharedNodeGroup) {
        while (nodeSharedState->sharedNodeGroup->getNumRows() > 0) {
            writeAndResetNodeGroup(transaction, nodeSharedState->sharedNodeGroup,
                nodeSharedState->globalIndexBuilder, MemoryManager::Get(*clientContext),
                errorHandler, pageAllocator);
        }
    }
    if (nodeSharedState->globalIndexBuilder) {
        nodeSharedState->globalIndexBuilder->finalize(context, errorHandler);
        errorHandler.flushStoredErrors();
    }

    auto& nodeTable = nodeSharedState->table->cast<NodeTable>();
    for (auto& index : nodeTable.getIndexes()) {
        index.finalize(clientContext);
    }
    // we want to flush all index errors before children call finalize
    // as the children (if they are table function calls) are responsible for populating the errors
    // and sending it to the warning context
    PhysicalOperator::finalize(context);

    // if the child is a subquery it will not send the errors to the warning context
    // sends any remaining warnings in this case
    // if the child is a table function call it will have already sent the warnings so this line
    // will do nothing
    WarningContext::Get(*clientContext)->defaultPopulateAllWarnings(context->queryID);
}

void NodeBatchInsert::finalizeInternal(ExecutionContext* context) {
    auto outputMsg = stringFormat("{} tuples have been copied to the {} table.",
        sharedState->getNumRows() - sharedState->getNumErroredRows(), info->tableName);
    auto clientContext = context->clientContext;
    FactorizedTableUtils::appendStringToTable(sharedState->fTable.get(), outputMsg,
        MemoryManager::Get(*clientContext));

    const auto warningCount =
        WarningContext::Get(*clientContext)->getWarningCount(context->queryID);
    if (warningCount > 0) {
        auto warningMsg =
            stringFormat("{} warnings encountered during copy. Use 'CALL "
                         "show_warnings() RETURN *' to view the actual warnings. Query ID: {}",
                warningCount, context->queryID);
        FactorizedTableUtils::appendStringToTable(sharedState->fTable.get(), warningMsg,
            MemoryManager::Get(*clientContext));
    }
}

} // namespace processor
} // namespace lbug
