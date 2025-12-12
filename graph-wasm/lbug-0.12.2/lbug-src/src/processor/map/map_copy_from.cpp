#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "planner/operator/logical_partitioner.h"
#include "planner/operator/persistent/logical_copy_from.h"
#include "processor/expression_mapper.h"
#include "processor/operator/index_lookup.h"
#include "processor/operator/partitioner.h"
#include "processor/operator/persistent/copy_rel_batch_insert.h"
#include "processor/operator/persistent/node_batch_insert.h"
#include "processor/operator/persistent/rel_batch_insert.h"
#include "processor/operator/table_function_call.h"
#include "processor/plan_mapper.h"
#include "processor/result/factorized_table_util.h"
#include "processor/warning_context.h"
#include "storage/storage_manager.h"
#include "storage/table/node_table.h"
#include "storage/table/rel_table.h"

using namespace lbug::binder;
using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapCopyFrom(const LogicalOperator* logicalOperator) {
    const auto& copyFrom = logicalOperator->constCast<LogicalCopyFrom>();
    WarningContext::Get(*clientContext)
        ->setIgnoreErrorsForCurrentQuery(copyFrom.getInfo()->getIgnoreErrorsOption());
    switch (copyFrom.getInfo()->tableType) {
    case TableType::NODE: {
        return mapCopyNodeFrom(logicalOperator);
    }
    case TableType::REL: {
        return mapCopyRelFrom(logicalOperator);
    }
    default:
        KU_UNREACHABLE;
    }
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapCopyNodeFrom(
    const LogicalOperator* logicalOperator) {
    auto& copyFrom = logicalOperator->constCast<LogicalCopyFrom>();
    const auto copyFromInfo = copyFrom.getInfo();
    const auto outFSchema = copyFrom.getSchema();
    auto prevOperator = mapOperator(copyFrom.getChild(0).get());
    auto fTable =
        FactorizedTableUtils::getSingleStringColumnFTable(MemoryManager::Get(*clientContext));

    auto sharedState = std::make_shared<NodeBatchInsertSharedState>(fTable);
    if (prevOperator->getOperatorType() == PhysicalOperatorType::TABLE_FUNCTION_CALL) {
        const auto call = prevOperator->ptrCast<TableFunctionCall>();
        sharedState->tableFuncSharedState = call->getSharedState().get();
    }
    std::vector<std::unique_ptr<evaluator::ExpressionEvaluator>> columnEvaluators;
    auto exprMapper = ExpressionMapper(outFSchema);
    for (auto& expr : copyFromInfo->columnExprs) {
        columnEvaluators.push_back(exprMapper.getEvaluator(expr));
    }
    std::vector<LogicalType> warningColumnTypes;
    for (auto& column : copyFromInfo->getWarningColumns()) {
        warningColumnTypes.push_back(column->getDataType().copy());
    }
    auto info = std::make_unique<NodeBatchInsertInfo>(copyFromInfo->tableName,
        std::move(warningColumnTypes), std::move(columnEvaluators),
        copyFromInfo->columnEvaluateTypes);
    auto printInfo = std::make_unique<NodeBatchInsertPrintInfo>(copyFromInfo->tableName);
    auto batchInsert = std::make_unique<NodeBatchInsert>(std::move(info), std::move(sharedState),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
    batchInsert->setDescriptor(std::make_unique<ResultSetDescriptor>(copyFrom.getSchema()));
    return batchInsert;
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapPartitioner(
    const LogicalOperator* logicalOperator) {
    auto& logicalPartitioner = logicalOperator->constCast<LogicalPartitioner>();
    auto prevOperator = mapOperator(logicalPartitioner.getChild(0).get());
    auto outFSchema = logicalPartitioner.getSchema();
    auto& copyFromInfo = logicalPartitioner.copyFromInfo;
    auto& extraInfo = copyFromInfo.extraInfo->constCast<ExtraBoundCopyRelInfo>();
    PartitionerInfo partitionerInfo;
    partitionerInfo.relOffsetDataPos =
        getDataPos(*logicalPartitioner.getInfo().offset, *outFSchema);
    partitionerInfo.infos.reserve(logicalPartitioner.getInfo().getNumInfos());
    for (auto i = 0u; i < logicalPartitioner.getInfo().getNumInfos(); i++) {
        partitionerInfo.infos.emplace_back(logicalPartitioner.getInfo().getInfo(i).keyIdx,
            PartitionerFunctions::partitionRelData);
    }
    std::vector<LogicalType> columnTypes;
    evaluator::evaluator_vector_t columnEvaluators;
    auto exprMapper = ExpressionMapper(outFSchema);
    for (auto& expr : copyFromInfo.columnExprs) {
        columnTypes.push_back(expr->getDataType().copy());
        columnEvaluators.push_back(exprMapper.getEvaluator(expr));
    }
    for (auto idx : extraInfo.internalIDColumnIndices) {
        columnTypes[idx] = LogicalType::INTERNAL_ID();
    }
    auto dataInfo = PartitionerDataInfo(copyFromInfo.tableName, extraInfo.fromTableName,
        extraInfo.toTableName, LogicalType::copy(columnTypes), std::move(columnEvaluators),
        copyFromInfo.columnEvaluateTypes);
    auto sharedState =
        std::make_shared<CopyPartitionerSharedState>(*MemoryManager::Get(*clientContext));
    expression_vector expressions;
    for (auto& info : partitionerInfo.infos) {
        expressions.push_back(copyFromInfo.columnExprs[info.keyIdx]);
    }
    auto printInfo = std::make_unique<PartitionerPrintInfo>(expressions);
    auto partitioner =
        std::make_unique<Partitioner>(std::move(partitionerInfo), std::move(dataInfo),
            std::move(sharedState), std::move(prevOperator), getOperatorID(), std::move(printInfo));
    partitioner->setDescriptor(std::make_unique<ResultSetDescriptor>(outFSchema));
    return partitioner;
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapCopyRelFrom(
    const LogicalOperator* logicalOperator) {
    auto& copyFrom = logicalOperator->constCast<LogicalCopyFrom>();
    const auto copyFromInfo = copyFrom.getInfo();
    auto partitioner = mapOperator(copyFrom.getChild(0).get());
    KU_ASSERT(partitioner->getOperatorType() == PhysicalOperatorType::PARTITIONER);
    auto partitionerSharedState = partitioner->ptrCast<Partitioner>()->getSharedState();
    const auto catalog = Catalog::Get(*clientContext);
    const auto transaction = transaction::Transaction::Get(*clientContext);
    auto extraInfo = copyFromInfo->extraInfo->constCast<ExtraBoundCopyRelInfo>();
    auto fromTableID =
        catalog->getTableCatalogEntry(transaction, extraInfo.fromTableName)->getTableID();
    auto toTableID =
        catalog->getTableCatalogEntry(transaction, extraInfo.toTableName)->getTableID();
    std::vector<LogicalType> warningColumnTypes;
    for (auto& column : copyFromInfo->getWarningColumns()) {
        warningColumnTypes.push_back(column->getDataType().copy());
    }
    auto fTable =
        FactorizedTableUtils::getSingleStringColumnFTable(MemoryManager::Get(*clientContext));
    auto batchInsertSharedState = std::make_shared<BatchInsertSharedState>(fTable);
    // If the table entry doesn't exist, assume both directions
    std::vector directions = {RelDataDirection::FWD, RelDataDirection::BWD};
    if (catalog->containsTable(transaction, copyFromInfo->tableName)) {
        const auto& relGroupEntry =
            catalog->getTableCatalogEntry(transaction, copyFromInfo->tableName)
                ->constCast<RelGroupCatalogEntry>();
        directions = relGroupEntry.getRelDataDirections();
    }

    auto sink = std::make_unique<DummySimpleSink>(fTable, getOperatorID());
    for (auto direction : directions) {
        auto insertInfo = std::make_unique<RelBatchInsertInfo>(copyFromInfo->tableName,
            copyVector(warningColumnTypes), fromTableID, toTableID, direction);
        auto printInfo = std::make_unique<RelBatchInsertPrintInfo>(copyFromInfo->tableName);
        auto progress = std::make_shared<RelBatchInsertProgressSharedState>();
        auto batchInsert = std::make_unique<RelBatchInsert>(std::move(insertInfo),
            partitionerSharedState, batchInsertSharedState, getOperatorID(), std::move(printInfo),
            progress, std::make_unique<CopyRelBatchInsert>());
        batchInsert->setDescriptor(std::make_unique<ResultSetDescriptor>(copyFrom.getSchema()));
        sink->addChild(std::move(batchInsert));
    }
    sink->addChild(std::move(partitioner));
    return sink;
}

} // namespace processor
} // namespace lbug
