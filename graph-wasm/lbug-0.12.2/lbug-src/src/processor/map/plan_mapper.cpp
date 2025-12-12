#include "processor/plan_mapper.h"

#include "main/client_context.h"
#include "main/database.h"
#include "planner/operator/logical_plan.h"
#include "processor/operator/profile.h"
#include "storage/storage_manager.h"
#include "storage/table/node_table.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::storage;

namespace lbug {
namespace processor {

PlanMapper::PlanMapper(ExecutionContext* executionContext)
    : executionContext{executionContext}, physicalOperatorID{0} {
    clientContext = executionContext->clientContext;
    mapperExtensions = clientContext->getDatabase()->getMapperExtensions();
}

std::unique_ptr<PhysicalPlan> PlanMapper::getPhysicalPlan(const LogicalPlan* logicalPlan,
    const expression_vector& expressions, main::QueryResultType resultType,
    ArrowResultConfig arrowConfig) {
    auto root = mapOperator(logicalPlan->getLastOperator().get());
    if (!root->isSink()) {
        if (resultType == main::QueryResultType::ARROW) {
            root = createArrowResultCollector(arrowConfig, expressions, logicalPlan->getSchema(),
                std::move(root));
        } else {
            root = createResultCollector(AccumulateType::REGULAR, expressions,
                logicalPlan->getSchema(), std::move(root));
        }
    }
    auto physicalPlan = std::make_unique<PhysicalPlan>(std::move(root));
    if (logicalPlan->isProfile()) {
        physicalPlan->lastOperator->ptrCast<Profile>()->setPhysicalPlan(physicalPlan.get());
    }
    return physicalPlan;
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapOperator(const LogicalOperator* logicalOperator) {
    std::unique_ptr<PhysicalOperator> physicalOperator;
    switch (logicalOperator->getOperatorType()) {
    case LogicalOperatorType::ACCUMULATE: {
        physicalOperator = mapAccumulate(logicalOperator);
    } break;
    case LogicalOperatorType::AGGREGATE: {
        physicalOperator = mapAggregate(logicalOperator);
    } break;
    case LogicalOperatorType::ALTER: {
        physicalOperator = mapAlter(logicalOperator);
    } break;
    case LogicalOperatorType::ATTACH_DATABASE: {
        physicalOperator = mapAttachDatabase(logicalOperator);
    } break;
    case LogicalOperatorType::COPY_FROM: {
        physicalOperator = mapCopyFrom(logicalOperator);
    } break;
    case LogicalOperatorType::COPY_TO: {
        physicalOperator = mapCopyTo(logicalOperator);
    } break;
    case LogicalOperatorType::CREATE_MACRO: {
        physicalOperator = mapCreateMacro(logicalOperator);
    } break;
    case LogicalOperatorType::CREATE_SEQUENCE: {
        physicalOperator = mapCreateSequence(logicalOperator);
    } break;
    case LogicalOperatorType::CREATE_TABLE: {
        physicalOperator = mapCreateTable(logicalOperator);
    } break;
    case LogicalOperatorType::CREATE_TYPE: {
        physicalOperator = mapCreateType(logicalOperator);
    } break;
    case LogicalOperatorType::CROSS_PRODUCT: {
        physicalOperator = mapCrossProduct(logicalOperator);
    } break;
    case LogicalOperatorType::DELETE: {
        physicalOperator = mapDelete(logicalOperator);
    } break;
    case LogicalOperatorType::DETACH_DATABASE: {
        physicalOperator = mapDetachDatabase(logicalOperator);
    } break;
    case LogicalOperatorType::DISTINCT: {
        physicalOperator = mapDistinct(logicalOperator);
    } break;
    case LogicalOperatorType::DROP: {
        physicalOperator = mapDrop(logicalOperator);
    } break;
    case LogicalOperatorType::DUMMY_SCAN: {
        physicalOperator = mapDummyScan(logicalOperator);
    } break;
    case LogicalOperatorType::DUMMY_SINK: {
        physicalOperator = mapDummySink(logicalOperator);
    } break;
    case LogicalOperatorType::EMPTY_RESULT: {
        physicalOperator = mapEmptyResult(logicalOperator);
    } break;
    case LogicalOperatorType::EXPLAIN: {
        physicalOperator = mapExplain(logicalOperator);
    } break;
    case LogicalOperatorType::EXPRESSIONS_SCAN: {
        physicalOperator = mapExpressionsScan(logicalOperator);
    } break;
    case LogicalOperatorType::EXTEND: {
        physicalOperator = mapExtend(logicalOperator);
    } break;
    case LogicalOperatorType::EXTENSION: {
        physicalOperator = mapExtension(logicalOperator);
    } break;
    case LogicalOperatorType::EXPORT_DATABASE: {
        physicalOperator = mapExportDatabase(logicalOperator);
    } break;
    case LogicalOperatorType::FLATTEN: {
        physicalOperator = mapFlatten(logicalOperator);
    } break;
    case LogicalOperatorType::FILTER: {
        physicalOperator = mapFilter(logicalOperator);
    } break;
    case LogicalOperatorType::HASH_JOIN: {
        physicalOperator = mapHashJoin(logicalOperator);
    } break;
    case LogicalOperatorType::IMPORT_DATABASE: {
        physicalOperator = mapImportDatabase(logicalOperator);
    } break;
    case LogicalOperatorType::INDEX_LOOK_UP: {
        physicalOperator = mapIndexLookup(logicalOperator);
    } break;
    case LogicalOperatorType::INTERSECT: {
        physicalOperator = mapIntersect(logicalOperator);
    } break;
    case LogicalOperatorType::INSERT: {
        physicalOperator = mapInsert(logicalOperator);
    } break;
    case LogicalOperatorType::LIMIT: {
        physicalOperator = mapLimit(logicalOperator);
    } break;
    case LogicalOperatorType::MERGE: {
        physicalOperator = mapMerge(logicalOperator);
    } break;
    case LogicalOperatorType::MULTIPLICITY_REDUCER: {
        physicalOperator = mapMultiplicityReducer(logicalOperator);
    } break;
    case LogicalOperatorType::NODE_LABEL_FILTER: {
        physicalOperator = mapNodeLabelFilter(logicalOperator);
    } break;
    case LogicalOperatorType::NOOP: {
        physicalOperator = mapNoop(logicalOperator);
    } break;
    case LogicalOperatorType::ORDER_BY: {
        physicalOperator = mapOrderBy(logicalOperator);
    } break;
    case LogicalOperatorType::PARTITIONER: {
        physicalOperator = mapPartitioner(logicalOperator);
    } break;
    case LogicalOperatorType::PATH_PROPERTY_PROBE: {
        physicalOperator = mapPathPropertyProbe(logicalOperator);
    } break;
    case LogicalOperatorType::PROJECTION: {
        physicalOperator = mapProjection(logicalOperator);
    } break;
    case LogicalOperatorType::RECURSIVE_EXTEND: {
        physicalOperator = mapRecursiveExtend(logicalOperator);
    } break;
    case LogicalOperatorType::SCAN_NODE_TABLE: {
        physicalOperator = mapScanNodeTable(logicalOperator);
    } break;
    case LogicalOperatorType::SEMI_MASKER: {
        physicalOperator = mapSemiMasker(logicalOperator);
    } break;
    case LogicalOperatorType::SET_PROPERTY: {
        physicalOperator = mapSetProperty(logicalOperator);
    } break;
    case LogicalOperatorType::STANDALONE_CALL: {
        physicalOperator = mapStandaloneCall(logicalOperator);
    } break;
    case LogicalOperatorType::TABLE_FUNCTION_CALL: {
        physicalOperator = mapTableFunctionCall(logicalOperator);
    } break;
    case LogicalOperatorType::TRANSACTION: {
        physicalOperator = mapTransaction(logicalOperator);
    } break;
    case LogicalOperatorType::UNION_ALL: {
        physicalOperator = mapUnionAll(logicalOperator);
    } break;
    case LogicalOperatorType::UNWIND: {
        physicalOperator = mapUnwind(logicalOperator);
    } break;
    case LogicalOperatorType::USE_DATABASE: {
        physicalOperator = mapUseDatabase(logicalOperator);
    } break;
    case LogicalOperatorType::EXTENSION_CLAUSE: {
        physicalOperator = mapExtensionClause(logicalOperator);
    } break;
    default:
        KU_UNREACHABLE;
    }
    if (!logicalOpToPhysicalOpMap.contains(logicalOperator)) {
        logicalOpToPhysicalOpMap.insert({logicalOperator, physicalOperator.get()});
    }
    return physicalOperator;
}

std::vector<DataPos> PlanMapper::getDataPos(const expression_vector& expressions,
    const Schema& schema) {
    std::vector<DataPos> result;
    for (auto& expression : expressions) {
        result.emplace_back(getDataPos(*expression, schema));
    }
    return result;
}

FactorizedTableSchema PlanMapper::createFlatFTableSchema(const expression_vector& expressions,
    const Schema& schema) {
    auto tableSchema = FactorizedTableSchema();
    for (auto& expr : expressions) {
        auto dataPos = getDataPos(*expr, schema);
        auto columnSchema = ColumnSchema(false /* isUnFlat */, dataPos.dataChunkPos,
            LogicalTypeUtils::getRowLayoutSize(expr->getDataType()));
        tableSchema.appendColumn(std::move(columnSchema));
    }
    return tableSchema;
}

std::unique_ptr<SemiMask> PlanMapper::createSemiMask(table_id_t tableID) const {
    auto table = StorageManager::Get(*clientContext)->getTable(tableID)->ptrCast<NodeTable>();
    return SemiMaskUtil::createMask(
        table->getNumTotalRows(transaction::Transaction::Get(*clientContext)));
}

} // namespace processor
} // namespace lbug
