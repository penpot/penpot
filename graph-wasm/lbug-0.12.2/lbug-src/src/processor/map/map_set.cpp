#include "binder/expression/property_expression.h"
#include "binder/expression/rel_expression.h"
#include "main/client_context.h"
#include "planner/operator/persistent/logical_set.h"
#include "processor/expression_mapper.h"
#include "processor/operator/persistent/set.h"
#include "processor/plan_mapper.h"
#include "storage/storage_manager.h"
#include "storage/table/table.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::planner;
using namespace lbug::evaluator;
using namespace lbug::transaction;
using namespace lbug::storage;

namespace lbug {
namespace processor {

static column_id_t getColumnID(const TableCatalogEntry& entry,
    const PropertyExpression& propertyExpr) {
    auto columnID = INVALID_COLUMN_ID;
    if (propertyExpr.hasProperty(entry.getTableID())) {
        columnID = entry.getColumnID(propertyExpr.getPropertyName());
    }
    return columnID;
}

static NodeTableSetInfo getNodeTableSetInfo(const TableCatalogEntry& entry, const Expression& expr,
    StorageManager* storageManager) {
    auto table = storageManager->getTable(entry.getTableID())->ptrCast<NodeTable>();
    auto columnID = getColumnID(entry, expr.constCast<PropertyExpression>());
    return NodeTableSetInfo(table, columnID);
}

static RelTableSetInfo getRelTableSetInfo(const RelGroupCatalogEntry& entry, table_id_t srcTableID,
    table_id_t dstTableID, const Expression& expr, StorageManager* storageManager) {
    auto relEntryInfo = entry.getRelEntryInfo(srcTableID, dstTableID);
    auto table = storageManager->getTable(relEntryInfo->oid)->ptrCast<RelTable>();
    auto columnID = getColumnID(entry, expr.constCast<PropertyExpression>());
    return RelTableSetInfo(table, columnID);
}

std::unique_ptr<NodeSetExecutor> PlanMapper::getNodeSetExecutor(
    const BoundSetPropertyInfo& boundInfo, const Schema& schema) const {
    auto& node = boundInfo.pattern->constCast<NodeExpression>();
    auto nodeIDPos = getDataPos(*node.getInternalID(), schema);
    auto& property = boundInfo.column->constCast<PropertyExpression>();
    auto columnVectorPos = DataPos::getInvalidPos();
    if (schema.isExpressionInScope(property)) {
        columnVectorPos = getDataPos(property, schema);
    }
    auto exprMapper = ExpressionMapper(&schema);
    auto evaluator = exprMapper.getEvaluator(boundInfo.columnData);
    auto setInfo = NodeSetInfo(nodeIDPos, columnVectorPos, std::move(evaluator));
    if (node.isMultiLabeled()) {
        table_id_map_t<NodeTableSetInfo> tableInfos;
        for (auto entry : node.getEntries()) {
            auto tableID = entry->getTableID();
            auto tableInfo =
                getNodeTableSetInfo(*entry, property, StorageManager::Get(*clientContext));
            if (tableInfo.columnID == INVALID_COLUMN_ID) {
                continue;
            }
            tableInfos.insert({tableID, std::move(tableInfo)});
        }
        return std::make_unique<MultiLabelNodeSetExecutor>(std::move(setInfo),
            std::move(tableInfos));
    }
    KU_ASSERT(node.getNumEntries() == 1);
    auto tableInfo =
        getNodeTableSetInfo(*node.getEntry(0), property, StorageManager::Get(*clientContext));
    return std::make_unique<SingleLabelNodeSetExecutor>(std::move(setInfo), std::move(tableInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapSetProperty(
    const LogicalOperator* logicalOperator) {
    auto set = logicalOperator->constPtrCast<LogicalSetProperty>();
    switch (set->getTableType()) {
    case TableType::NODE: {
        return mapSetNodeProperty(logicalOperator);
    }
    case TableType::REL: {
        return mapSetRelProperty(logicalOperator);
    }
    default:
        KU_UNREACHABLE;
    }
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapSetNodeProperty(
    const LogicalOperator* logicalOperator) {
    auto set = logicalOperator->constPtrCast<LogicalSetProperty>();
    auto inSchema = set->getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    std::vector<std::unique_ptr<NodeSetExecutor>> executors;
    for (auto& info : set->getInfos()) {
        executors.push_back(getNodeSetExecutor(info, *inSchema));
    }
    std::vector<binder::expression_pair> expressions;
    for (auto& info : set->getInfos()) {
        expressions.emplace_back(info.column, info.columnData);
    }
    auto printInfo = std::make_unique<SetPropertyPrintInfo>(expressions);
    return std::make_unique<SetNodeProperty>(std::move(executors), std::move(prevOperator),
        getOperatorID(), std::move(printInfo));
}

std::unique_ptr<RelSetExecutor> PlanMapper::getRelSetExecutor(const BoundSetPropertyInfo& boundInfo,
    const Schema& schema) const {
    auto& rel = boundInfo.pattern->constCast<RelExpression>();
    auto srcNodeIDPos = getDataPos(*rel.getSrcNode()->getInternalID(), schema);
    auto dstNodeIDPos = getDataPos(*rel.getDstNode()->getInternalID(), schema);
    auto relIDPos = getDataPos(*rel.getInternalID(), schema);
    auto& property = boundInfo.column->constCast<PropertyExpression>();
    auto columnVectorPos = DataPos::getInvalidPos();
    if (schema.isExpressionInScope(property)) {
        columnVectorPos = getDataPos(property, schema);
    }
    auto exprMapper = ExpressionMapper(&schema);
    auto evaluator = exprMapper.getEvaluator(boundInfo.columnData);
    auto info =
        RelSetInfo(srcNodeIDPos, dstNodeIDPos, relIDPos, columnVectorPos, std::move(evaluator));
    if (rel.isMultiLabeled()) {
        table_id_map_t<RelTableSetInfo> tableInfos;
        for (auto entry : rel.getEntries()) {
            auto& relGroupEntry = entry->constCast<RelGroupCatalogEntry>();
            for (auto& relEntryInfo : relGroupEntry.getRelEntryInfos()) {
                auto srcTableID = relEntryInfo.nodePair.srcTableID;
                auto dstTableID = relEntryInfo.nodePair.dstTableID;
                auto tableInfo = getRelTableSetInfo(relGroupEntry, srcTableID, dstTableID, property,
                    StorageManager::Get(*clientContext));
                if (tableInfo.columnID == INVALID_COLUMN_ID) {
                    continue;
                }
                tableInfos.insert({tableInfo.table->getTableID(), std::move(tableInfo)});
            }
        }
        return std::make_unique<MultiLabelRelSetExecutor>(std::move(info), std::move(tableInfos));
    }
    KU_ASSERT(rel.getNumEntries() == 1);
    auto& relGroupEntry = rel.getEntry(0)->constCast<RelGroupCatalogEntry>();
    auto fromToNodePair = relGroupEntry.getSingleRelEntryInfo().nodePair;
    auto tableInfo = getRelTableSetInfo(relGroupEntry, fromToNodePair.srcTableID,
        fromToNodePair.dstTableID, property, StorageManager::Get(*clientContext));
    return std::make_unique<SingleLabelRelSetExecutor>(std::move(info), std::move(tableInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapSetRelProperty(
    const LogicalOperator* logicalOperator) {
    auto set = logicalOperator->constPtrCast<LogicalSetProperty>();
    auto inSchema = set->getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    std::vector<std::unique_ptr<RelSetExecutor>> executors;
    for (auto& info : set->getInfos()) {
        executors.push_back(getRelSetExecutor(info, *inSchema));
    }
    std::vector<expression_pair> expressions;
    for (auto& info : set->getInfos()) {
        expressions.emplace_back(info.column, info.columnData);
    }
    auto printInfo = std::make_unique<SetPropertyPrintInfo>(expressions);
    return std::make_unique<SetRelProperty>(std::move(executors), std::move(prevOperator),
        getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
