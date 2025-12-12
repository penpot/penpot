#include "function/gds/gds.h"

#include "binder/binder.h"
#include "binder/expression/rel_expression.h"
#include "binder/query/reading_clause/bound_table_function_call.h"
#include "catalog/catalog.h"
#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "common/exception/binder.h"
#include "function/table/bind_input.h"
#include "graph/graph_entry_set.h"
#include "graph/on_disk_graph.h"
#include "parser/parser.h"
#include "planner/operator/logical_table_function_call.h"
#include "planner/operator/sip/logical_semi_masker.h"
#include "planner/planner.h"
#include "processor/operator/table_function_call.h"
#include "processor/plan_mapper.h"

using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::binder;
using namespace lbug::main;
using namespace lbug::graph;
using namespace lbug::processor;
using namespace lbug::planner;

namespace lbug {
namespace function {

void GDSFuncSharedState::setGraphNodeMask(std::unique_ptr<NodeOffsetMaskMap> maskMap) {
    auto onDiskGraph = ku_dynamic_cast<OnDiskGraph*>(graph.get());
    onDiskGraph->setNodeOffsetMask(maskMap.get());
    graphNodeMask = std::move(maskMap);
}

static expression_vector getResultColumns(const std::string& cypher, ClientContext* context) {
    auto parsedStatements = parser::Parser::parseQuery(cypher);
    KU_ASSERT(parsedStatements.size() == 1);
    auto binder = Binder(context);
    auto boundStatement = binder.bind(*parsedStatements[0]);
    return boundStatement->getStatementResult()->getColumns();
}

static void validateNodeProjected(const table_id_set_t& connectedNodeTableIDSet,
    const table_id_set_t& projectedNodeIDSet, const std::string& relName, Catalog* catalog,
    transaction::Transaction* transaction) {
    for (auto id : connectedNodeTableIDSet) {
        if (!projectedNodeIDSet.contains(id)) {
            auto entryName = catalog->getTableCatalogEntry(transaction, id)->getName();
            throw BinderException(
                stringFormat("{} is connected to {} but not projected.", entryName, relName));
        }
    }
}

static void validateRelSrcDstNodeAreProjected(const TableCatalogEntry& entry,
    const table_id_set_t& projectedNodeIDSet, Catalog* catalog,
    transaction::Transaction* transaction) {
    auto& relEntry = entry.constCast<RelGroupCatalogEntry>();
    validateNodeProjected(relEntry.getSrcNodeTableIDSet(), projectedNodeIDSet, relEntry.getName(),
        catalog, transaction);
    validateNodeProjected(relEntry.getDstNodeTableIDSet(), projectedNodeIDSet, relEntry.getName(),
        catalog, transaction);
}

NativeGraphEntry GDSFunction::bindGraphEntry(ClientContext& context, const std::string& name) {
    auto set = GraphEntrySet::Get(context);
    set->validateGraphExist(name);
    auto entry = set->getEntry(name);
    if (entry->type != GraphEntryType::NATIVE) {
        throw BinderException("AA");
    }
    return bindGraphEntry(context, entry->cast<ParsedNativeGraphEntry>());
}

static NativeGraphEntryTableInfo bindNodeEntry(ClientContext& context, const std::string& tableName,
    const std::string& predicate) {
    auto catalog = Catalog::Get(context);
    auto transaction = transaction::Transaction::Get(context);
    auto nodeEntry = catalog->getTableCatalogEntry(transaction, tableName);
    if (nodeEntry->getType() != CatalogEntryType::NODE_TABLE_ENTRY) {
        throw BinderException(stringFormat("{} is not a NODE table.", tableName));
    }
    if (!predicate.empty()) {
        auto cypher = stringFormat("MATCH (n:`{}`) RETURN n, {}", nodeEntry->getName(), predicate);
        auto columns = getResultColumns(cypher, &context);
        KU_ASSERT(columns.size() == 2);
        return {nodeEntry, columns[0], columns[1]};
    } else {
        auto cypher = stringFormat("MATCH (n:`{}`) RETURN n", nodeEntry->getName());
        auto columns = getResultColumns(cypher, &context);
        KU_ASSERT(columns.size() == 1);
        return {nodeEntry, columns[0], nullptr /* empty predicate */};
    }
}

static NativeGraphEntryTableInfo bindRelEntry(ClientContext& context, const std::string& tableName,
    const std::string& predicate) {
    auto catalog = Catalog::Get(context);
    auto transaction = transaction::Transaction::Get(context);
    auto relEntry = catalog->getTableCatalogEntry(transaction, tableName);
    if (relEntry->getType() != CatalogEntryType::REL_GROUP_ENTRY) {
        throw BinderException(
            stringFormat("{} has catalog entry type. REL entry was expected.", tableName));
    }
    if (!predicate.empty()) {
        auto cypher =
            stringFormat("MATCH ()-[r:`{}`]->() RETURN r, {}", relEntry->getName(), predicate);
        auto columns = getResultColumns(cypher, &context);
        KU_ASSERT(columns.size() == 2);
        return {relEntry, columns[0], columns[1]};
    } else {
        auto cypher = stringFormat("MATCH ()-[r:`{}`]->() RETURN r", relEntry->getName());
        auto columns = getResultColumns(cypher, &context);
        KU_ASSERT(columns.size() == 1);
        return {relEntry, columns[0], nullptr /* empty predicate */};
    }
}

NativeGraphEntry GDSFunction::bindGraphEntry(ClientContext& context,
    const ParsedNativeGraphEntry& entry) {
    auto catalog = Catalog::Get(context);
    auto transaction = transaction::Transaction::Get(context);
    auto result = NativeGraphEntry();
    table_id_set_t projectedNodeTableIDSet;
    for (auto& nodeInfo : entry.nodeInfos) {
        auto boundInfo = bindNodeEntry(context, nodeInfo.tableName, nodeInfo.predicate);
        projectedNodeTableIDSet.insert(boundInfo.entry->getTableID());
        result.nodeInfos.push_back(std::move(boundInfo));
    }
    for (auto& relInfo : entry.relInfos) {
        if (catalog->containsTable(transaction, relInfo.tableName)) {
            auto boundInfo = bindRelEntry(context, relInfo.tableName, relInfo.predicate);
            validateRelSrcDstNodeAreProjected(*boundInfo.entry, projectedNodeTableIDSet, catalog,
                transaction);
            result.relInfos.push_back(std::move(boundInfo));
        } else {
            throw BinderException(stringFormat("{} is not a REL table.", relInfo.tableName));
        }
    }
    return result;
}

std::shared_ptr<binder::Expression> GDSFunction::bindRelOutput(const TableFuncBindInput& bindInput,
    const std::vector<catalog::TableCatalogEntry*>& relEntries,
    std::shared_ptr<NodeExpression> srcNode, std::shared_ptr<NodeExpression> dstNode,
    const std::optional<std::string>& name, const std::optional<uint64_t>& yieldVariableIdx) {
    std::string relColumnName = name.value_or(REL_COLUMN_NAME);
    StringUtils::toLower(relColumnName);
    if (!bindInput.yieldVariables.empty()) {
        relColumnName =
            bindColumnName(bindInput.yieldVariables[yieldVariableIdx.value_or(0)], relColumnName);
    }
    auto rel = bindInput.binder->createNonRecursiveQueryRel(relColumnName, relEntries, srcNode,
        dstNode, RelDirectionType::SINGLE);
    bindInput.binder->addToScope(REL_COLUMN_NAME, rel);
    return rel;
}

std::shared_ptr<Expression> GDSFunction::bindNodeOutput(const TableFuncBindInput& bindInput,
    const std::vector<TableCatalogEntry*>& nodeEntries, const std::optional<std::string>& name,
    const std::optional<uint64_t>& yieldVariableIdx) {
    std::string nodeColumnName = name.value_or(NODE_COLUMN_NAME);
    StringUtils::toLower(nodeColumnName);
    if (!bindInput.yieldVariables.empty()) {
        nodeColumnName =
            bindColumnName(bindInput.yieldVariables[yieldVariableIdx.value_or(0)], nodeColumnName);
    }
    auto node = bindInput.binder->createQueryNode(nodeColumnName, nodeEntries);
    bindInput.binder->addToScope(nodeColumnName, node);
    return node;
}

std::string GDSFunction::bindColumnName(const parser::YieldVariable& yieldVariable,
    std::string expressionName) {
    if (yieldVariable.name != expressionName) {
        throw common::BinderException{
            common::stringFormat("Unknown variable name: {}.", yieldVariable.name)};
    }
    if (yieldVariable.hasAlias()) {
        return yieldVariable.alias;
    }
    return expressionName;
}

std::unique_ptr<TableFuncSharedState> GDSFunction::initSharedState(
    const TableFuncInitSharedStateInput& input) {
    auto bindData = input.bindData->constPtrCast<GDSBindData>();
    auto graph =
        std::make_unique<OnDiskGraph>(input.context->clientContext, bindData->graphEntry.copy());
    return std::make_unique<GDSFuncSharedState>(bindData->getResultTable(), std::move(graph));
}

std::vector<std::shared_ptr<LogicalOperator>> getNodeMaskPlanRoots(const GDSBindData& bindData,
    Planner* planner) {
    std::vector<std::shared_ptr<LogicalOperator>> nodeMaskPlanRoots;
    for (auto& nodeInfo : bindData.graphEntry.nodeInfos) {
        if (nodeInfo.predicate == nullptr) {
            continue;
        }
        auto& node = nodeInfo.nodeOrRel->constCast<NodeExpression>();
        planner->getCardinliatyEstimatorUnsafe().init(node);
        auto p = planner->getNodeSemiMaskPlan(SemiMaskTargetType::GDS_GRAPH_NODE, node,
            nodeInfo.predicate);
        nodeMaskPlanRoots.push_back(p.getLastOperator());
    }
    return nodeMaskPlanRoots;
};

void GDSFunction::getLogicalPlan(Planner* planner, const BoundReadingClause& readingClause,
    expression_vector predicates, LogicalPlan& plan) {
    auto& call = readingClause.constCast<BoundTableFunctionCall>();
    auto bindData = call.getBindData()->constPtrCast<GDSBindData>();
    auto op = std::make_shared<LogicalTableFunctionCall>(call.getTableFunc(), bindData->copy());
    for (auto root : getNodeMaskPlanRoots(*bindData, planner)) {
        op->addChild(root);
    }
    op->computeFactorizedSchema();
    planner->planReadOp(std::move(op), predicates, plan);

    auto nodeOutput = bindData->output[0]->ptrCast<NodeExpression>();
    KU_ASSERT(nodeOutput != nullptr);
    planner->getCardinliatyEstimatorUnsafe().init(*nodeOutput);
    auto scanPlan = planner->getNodePropertyScanPlan(*nodeOutput);
    if (scanPlan.isEmpty()) {
        return;
    }
    expression_vector joinConditions;
    joinConditions.push_back(nodeOutput->getInternalID());
    planner->appendHashJoin(joinConditions, JoinType::INNER, plan, scanPlan, plan);
}

std::unique_ptr<PhysicalOperator> GDSFunction::getPhysicalPlan(PlanMapper* planMapper,
    const LogicalOperator* logicalOp) {
    auto logicalCall = logicalOp->constPtrCast<LogicalTableFunctionCall>();
    auto bindData = logicalCall->getBindData()->copy();
    auto columns = bindData->columns;
    auto tableSchema = PlanMapper::createFlatFTableSchema(columns, *logicalCall->getSchema());
    auto table = std::make_shared<FactorizedTable>(
        storage::MemoryManager::Get(*planMapper->clientContext), tableSchema.copy());
    bindData->cast<GDSBindData>().setResultFTable(table);
    auto info = TableFunctionCallInfo();
    info.function = logicalCall->getTableFunc();
    info.bindData = std::move(bindData);
    auto initInput =
        TableFuncInitSharedStateInput(info.bindData.get(), planMapper->executionContext);
    auto sharedState = info.function.initSharedStateFunc(initInput);
    auto printInfo =
        std::make_unique<TableFunctionCallPrintInfo>(info.function.name, info.bindData->columns);
    auto call = std::make_unique<TableFunctionCall>(std::move(info), sharedState,
        planMapper->getOperatorID(), std::move(printInfo));
    if (logicalCall->getNumChildren() > 0u) {
        const auto funcSharedState = sharedState->ptrCast<GDSFuncSharedState>();
        funcSharedState->setGraphNodeMask(std::make_unique<NodeOffsetMaskMap>());
        auto maskMap = funcSharedState->getGraphNodeMaskMap();
        planMapper->addOperatorMapping(logicalOp, call.get());
        for (auto logicalRoot : logicalCall->getChildren()) {
            KU_ASSERT(logicalRoot->getNumChildren() == 1);
            auto child = logicalRoot->getChild(0);
            KU_ASSERT(child->getOperatorType() == LogicalOperatorType::SEMI_MASKER);
            auto logicalSemiMasker = child->ptrCast<LogicalSemiMasker>();
            logicalSemiMasker->addTarget(logicalOp);
            for (auto tableID : logicalSemiMasker->getNodeTableIDs()) {
                maskMap->addMask(tableID, planMapper->createSemiMask(tableID));
            }
            auto root = planMapper->mapOperator(logicalRoot.get());
            call->addChild(std::move(root));
        }
        planMapper->eraseOperatorMapping(logicalOp);
    }
    planMapper->addOperatorMapping(logicalOp, call.get());
    physical_op_vector_t children;
    auto dummySink = std::make_unique<DummySink>(std::move(call), planMapper->getOperatorID());
    dummySink->setDescriptor(std::make_unique<ResultSetDescriptor>(logicalCall->getSchema()));
    children.push_back(std::move(dummySink));
    return planMapper->createFTableScanAligned(columns, logicalCall->getSchema(), table,
        DEFAULT_VECTOR_CAPACITY, std::move(children));
}

} // namespace function
} // namespace lbug
