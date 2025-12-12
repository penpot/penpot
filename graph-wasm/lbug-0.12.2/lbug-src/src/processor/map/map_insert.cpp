#include "binder/expression/rel_expression.h"
#include "main/client_context.h"
#include "planner/operator/persistent/logical_insert.h"
#include "processor/expression_mapper.h"
#include "processor/operator/persistent/insert.h"
#include "processor/plan_mapper.h"
#include "storage/storage_manager.h"

using namespace lbug::evaluator;
using namespace lbug::planner;
using namespace lbug::storage;
using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace processor {

static std::vector<DataPos> populateReturnColumnsPos(const LogicalInsertInfo& info,
    const Schema& schema) {
    std::vector<DataPos> result;
    for (auto i = 0u; i < info.columnDataExprs.size(); ++i) {
        if (info.isReturnColumnExprs[i]) {
            result.emplace_back(schema.getExpressionPos(*info.columnExprs[i]));
        } else {
            result.push_back(DataPos::getInvalidPos());
        }
    }
    return result;
}

NodeInsertExecutor PlanMapper::getNodeInsertExecutor(const LogicalInsertInfo* boundInfo,
    const Schema& inSchema, const Schema& outSchema) const {
    auto& node = boundInfo->pattern->constCast<NodeExpression>();
    auto nodeIDPos = getDataPos(*node.getInternalID(), outSchema);
    auto columnsPos = populateReturnColumnsPos(*boundInfo, outSchema);
    auto info = NodeInsertInfo(nodeIDPos, columnsPos, boundInfo->conflictAction);
    auto storageManager = StorageManager::Get(*clientContext);
    KU_ASSERT(node.getNumEntries() == 1);
    ;
    auto table = storageManager->getTable(node.getEntry(0)->getTableID())->ptrCast<NodeTable>();
    evaluator_vector_t evaluators;
    auto exprMapper = ExpressionMapper(&inSchema);
    for (auto& expr : boundInfo->columnDataExprs) {
        evaluators.push_back(exprMapper.getEvaluator(expr));
    }
    auto tableInfo = NodeTableInsertInfo(table, std::move(evaluators));
    return NodeInsertExecutor(std::move(info), std::move(tableInfo));
}

RelInsertExecutor PlanMapper::getRelInsertExecutor(const LogicalInsertInfo* boundInfo,
    const Schema& inSchema, const Schema& outSchema) const {
    auto& rel = boundInfo->pattern->constCast<RelExpression>();
    auto srcNode = rel.getSrcNode();
    auto dstNode = rel.getDstNode();
    auto srcNodeIDPos = getDataPos(*srcNode->getInternalID(), inSchema);
    auto dstNodeIDPos = getDataPos(*dstNode->getInternalID(), inSchema);
    auto columnsPos = populateReturnColumnsPos(*boundInfo, outSchema);
    auto info = RelInsertInfo(srcNodeIDPos, dstNodeIDPos, std::move(columnsPos));
    auto storageManager = StorageManager::Get(*clientContext);
    KU_ASSERT(srcNode->getNumEntries() == 1 && dstNode->getNumEntries() == 1);
    auto srcTableID = srcNode->getEntry(0)->getTableID();
    auto dstTableID = dstNode->getEntry(0)->getTableID();
    KU_ASSERT(rel.getNumEntries() == 1);
    auto& relGroupEntry = rel.getEntry(0)->constCast<RelGroupCatalogEntry>();
    auto relEntryInfo = relGroupEntry.getRelEntryInfo(srcTableID, dstTableID);
    auto table = storageManager->getTable(relEntryInfo->oid)->ptrCast<RelTable>();
    evaluator_vector_t evaluators;
    auto exprMapper = ExpressionMapper(&outSchema);
    for (auto& expr : boundInfo->columnDataExprs) {
        evaluators.push_back(exprMapper.getEvaluator(expr));
    }
    auto tableInfo = RelTableInsertInfo(table, std::move(evaluators));
    return RelInsertExecutor(std::move(info), std::move(tableInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapInsert(const LogicalOperator* logicalOperator) {
    auto& logicalInsert = logicalOperator->constCast<LogicalInsert>();
    auto inSchema = logicalInsert.getChild(0)->getSchema();
    auto outSchema = logicalInsert.getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    std::vector<NodeInsertExecutor> nodeExecutors;
    std::vector<RelInsertExecutor> relExecutors;
    for (auto& info : logicalInsert.getInfos()) {
        switch (info.tableType) {
        case TableType::NODE: {
            nodeExecutors.push_back(getNodeInsertExecutor(&info, *inSchema, *outSchema));
        } break;
        case TableType::REL: {
            relExecutors.push_back(getRelInsertExecutor(&info, *inSchema, *outSchema));
        } break;
        default:
            KU_UNREACHABLE;
        }
    }
    expression_vector expressions;
    for (auto& info : logicalInsert.getInfos()) {
        for (auto& expr : info.columnExprs) {
            expressions.push_back(expr);
        }
    }
    auto printInfo =
        std::make_unique<InsertPrintInfo>(expressions, logicalInsert.getInfos()[0].conflictAction);
    return std::make_unique<Insert>(std::move(nodeExecutors), std::move(relExecutors),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
