#include "binder/expression/aggregate_function_expression.h"
#include "common/copy_constructors.h"
#include "common/types/types.h"
#include "planner/operator/logical_aggregate.h"
#include "processor/operator/aggregate/hash_aggregate.h"
#include "processor/operator/aggregate/hash_aggregate_scan.h"
#include "processor/operator/aggregate/simple_aggregate.h"
#include "processor/operator/aggregate/simple_aggregate_scan.h"
#include "processor/plan_mapper.h"
#include "processor/result/result_set_descriptor.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::function;
using namespace lbug::planner;

namespace lbug {
namespace processor {

static std::vector<AggregateInfo> getAggregateInputInfos(const expression_vector& keys,
    const expression_vector& aggregates, const Schema& schema) {
    // Collect unFlat groups from
    std::unordered_set<f_group_pos> groupByGroupPosSet;
    for (auto& expression : keys) {
        groupByGroupPosSet.insert(schema.getGroupPos(*expression));
    }
    std::unordered_set<f_group_pos> unFlatAggregateGroupPosSet;
    for (auto groupPos : schema.getGroupsPosInScope()) {
        if (groupByGroupPosSet.contains(groupPos)) {
            continue;
        }
        if (schema.getGroup(groupPos)->isFlat()) {
            continue;
        }
        unFlatAggregateGroupPosSet.insert(groupPos);
    }
    std::vector<AggregateInfo> result;
    for (auto& expression : aggregates) {
        auto aggregateVectorPos = DataPos::getInvalidPos();
        if (expression->getNumChildren() != 0) { // COUNT(*) has no children
            auto child = expression->getChild(0);
            aggregateVectorPos = DataPos{schema.getExpressionPos(*child)};
        }
        std::vector<data_chunk_pos_t> multiplicityChunksPos;
        for (auto& groupPos : unFlatAggregateGroupPosSet) {
            if (groupPos != aggregateVectorPos.dataChunkPos) {
                multiplicityChunksPos.push_back(groupPos);
            }
        }
        auto aggExpr = expression->constPtrCast<AggregateFunctionExpression>();
        auto distinctAggKeyType = aggExpr->isDistinct() ?
                                      expression->getChild(0)->getDataType().copy() :
                                      LogicalType::ANY();
        result.emplace_back(aggregateVectorPos, std::move(multiplicityChunksPos),
            std::move(distinctAggKeyType));
    }
    return result;
}

static expression_vector getKeyExpressions(const expression_vector& expressions,
    const Schema& schema, bool isFlat) {
    expression_vector result;
    for (auto& expression : expressions) {
        if (schema.getGroup(schema.getGroupPos(*expression))->isFlat() == isFlat) {
            result.emplace_back(expression);
        }
    }
    return result;
}

static std::vector<AggregateFunction> getAggFunctions(const expression_vector& aggregates) {
    std::vector<AggregateFunction> aggregateFunctions;
    for (auto& expression : aggregates) {
        auto aggExpr = expression->constPtrCast<AggregateFunctionExpression>();
        aggregateFunctions.push_back(aggExpr->getFunction().copy());
    }
    return aggregateFunctions;
}

static void writeAggResultWithNullToVector(ValueVector& vector, uint64_t pos,
    AggregateState* aggregateState) {
    auto isNull = aggregateState->constCast<AggregateStateWithNull>().isNull;
    vector.setNull(pos, isNull);
    if (!isNull) {
        aggregateState->writeToVector(&vector, pos);
    }
}

static void writeAggResultWithoutNullToVector(ValueVector& vector, uint64_t pos,
    AggregateState* aggregateState) {
    vector.setNull(pos, false);
    aggregateState->writeToVector(&vector, pos);
}

static std::vector<move_agg_result_to_vector_func> getMoveAggResultToVectorFuncs(
    std::vector<AggregateFunction>& aggregateFunctions) {
    std::vector<move_agg_result_to_vector_func> moveAggResultToVectorFuncs;
    for (auto& aggregateFunction : aggregateFunctions) {
        if (aggregateFunction.needToHandleNulls) {
            moveAggResultToVectorFuncs.push_back(writeAggResultWithoutNullToVector);
        } else {
            moveAggResultToVectorFuncs.push_back(writeAggResultWithNullToVector);
        }
    }
    return moveAggResultToVectorFuncs;
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapAggregate(const LogicalOperator* logicalOperator) {
    auto& agg = logicalOperator->constCast<LogicalAggregate>();
    auto aggregates = agg.getAggregates();
    auto outSchema = agg.getSchema();
    auto child = agg.getChild(0).get();
    auto inSchema = child->getSchema();
    auto prevOperator = mapOperator(child);
    if (agg.hasKeys()) {
        return createHashAggregate(agg.getKeys(), agg.getDependentKeys(), aggregates, inSchema,
            outSchema, std::move(prevOperator));
    }
    auto aggFunctions = getAggFunctions(aggregates);
    auto aggOutputPos = getDataPos(aggregates, *outSchema);
    auto aggregateInputInfos = getAggregateInputInfos(agg.getAllKeys(), aggregates, *inSchema);
    auto sharedState =
        make_shared<SimpleAggregateSharedState>(clientContext, aggFunctions, aggregateInputInfos);
    auto printInfo = std::make_unique<SimpleAggregatePrintInfo>(aggregates);
    auto aggregate = make_unique<SimpleAggregate>(sharedState, std::move(aggFunctions),
        copyVector(aggregateInputInfos), std::move(prevOperator), getOperatorID(),
        printInfo->copy());
    aggregate->setDescriptor(std::make_unique<ResultSetDescriptor>(inSchema));
    auto finalizer = std::make_unique<SimpleAggregateFinalize>(sharedState,
        std::move(aggregateInputInfos), getOperatorID(), printInfo->copy());
    finalizer->addChild(std::move(aggregate));
    aggFunctions = getAggFunctions(aggregates);
    auto scan = std::make_unique<SimpleAggregateScan>(sharedState,
        AggregateScanInfo{std::move(aggOutputPos), getMoveAggResultToVectorFuncs(aggFunctions)},
        getOperatorID(), printInfo->copy());
    scan->addChild(std::move(finalizer));
    return scan;
}

static FactorizedTableSchema getFactorizedTableSchema(const expression_vector& flatKeys,
    const expression_vector& unFlatKeys, const expression_vector& payloads,
    const std::vector<AggregateFunction>& aggregateFunctions) {
    auto isUnFlat = false;
    auto groupID = 0u;
    auto tableSchema = FactorizedTableSchema();
    for (auto& flatKey : flatKeys) {
        auto size = LogicalTypeUtils::getRowLayoutSize(flatKey->dataType);
        tableSchema.appendColumn(ColumnSchema(isUnFlat, groupID, size));
    }
    for (auto& unFlatKey : unFlatKeys) {
        auto size = LogicalTypeUtils::getRowLayoutSize(unFlatKey->dataType);
        tableSchema.appendColumn(ColumnSchema(isUnFlat, groupID, size));
    }
    for (auto& payload : payloads) {
        auto size = LogicalTypeUtils::getRowLayoutSize(payload->dataType);
        tableSchema.appendColumn(ColumnSchema(isUnFlat, groupID, size));
    }
    for (auto& aggregateFunc : aggregateFunctions) {
        tableSchema.appendColumn(
            ColumnSchema(isUnFlat, groupID, aggregateFunc.getAggregateStateSize()));
    }
    tableSchema.appendColumn(ColumnSchema(isUnFlat, groupID, sizeof(hash_t)));
    return tableSchema;
}

std::unique_ptr<PhysicalOperator> PlanMapper::createDistinctHashAggregate(
    const expression_vector& keys, const expression_vector& payloads, Schema* inSchema,
    Schema* outSchema, std::unique_ptr<PhysicalOperator> prevOperator) {
    return createHashAggregate(keys, payloads, expression_vector{} /* aggregates */, inSchema,
        outSchema, std::move(prevOperator));
}

// Payloads are also group by keys except that they are functional dependent on keys so we don't
// need to hash or compare payloads.
std::unique_ptr<PhysicalOperator> PlanMapper::createHashAggregate(const expression_vector& keys,
    const expression_vector& payloads, const expression_vector& aggregates, Schema* inSchema,
    Schema* outSchema, std::unique_ptr<PhysicalOperator> prevOperator) {
    // Create hash aggregate
    auto aggFunctions = getAggFunctions(aggregates);
    expression_vector allKeys;
    allKeys.insert(allKeys.end(), keys.begin(), keys.end());
    allKeys.insert(allKeys.end(), payloads.begin(), payloads.end());
    auto aggregateInputInfos = getAggregateInputInfos(allKeys, aggregates, *inSchema);
    auto flatKeys = getKeyExpressions(keys, *inSchema, true /* isFlat */);
    auto unFlatKeys = getKeyExpressions(keys, *inSchema, false /* isFlat */);
    std::vector<LogicalType> keyTypes, payloadTypes;
    for (auto& key : flatKeys) {
        keyTypes.push_back(key->getDataType().copy());
    }
    for (auto& key : unFlatKeys) {
        keyTypes.push_back(key->getDataType().copy());
    }
    for (auto& payload : payloads) {
        payloadTypes.push_back(payload->getDataType().copy());
    }
    auto tableSchema = getFactorizedTableSchema(flatKeys, unFlatKeys, payloads, aggFunctions);
    HashAggregateInfo aggregateInfo{getDataPos(flatKeys, *inSchema),
        getDataPos(unFlatKeys, *inSchema), getDataPos(payloads, *inSchema), std::move(tableSchema)};

    auto sharedState =
        std::make_shared<HashAggregateSharedState>(clientContext, std::move(aggregateInfo),
            aggFunctions, aggregateInputInfos, std::move(keyTypes), std::move(payloadTypes));
    auto printInfo = std::make_unique<HashAggregatePrintInfo>(allKeys, aggregates);
    auto aggregate = make_unique<HashAggregate>(sharedState, std::move(aggFunctions),
        std::move(aggregateInputInfos), std::move(prevOperator), getOperatorID(),
        printInfo->copy());
    aggregate->setDescriptor(std::make_unique<ResultSetDescriptor>(inSchema));
    // Create AggScan.
    expression_vector outputExpressions;
    outputExpressions.insert(outputExpressions.end(), flatKeys.begin(), flatKeys.end());
    outputExpressions.insert(outputExpressions.end(), unFlatKeys.begin(), unFlatKeys.end());
    outputExpressions.insert(outputExpressions.end(), payloads.begin(), payloads.end());
    auto aggOutputPos = getDataPos(aggregates, *outSchema);
    auto finalizer =
        std::make_unique<HashAggregateFinalize>(sharedState, getOperatorID(), printInfo->copy());
    finalizer->addChild(std::move(aggregate));
    aggFunctions = getAggFunctions(aggregates);
    auto scan =
        std::make_unique<HashAggregateScan>(sharedState, getDataPos(outputExpressions, *outSchema),
            AggregateScanInfo{std::move(aggOutputPos), getMoveAggResultToVectorFuncs(aggFunctions)},
            getOperatorID(), printInfo->copy());
    scan->addChild(std::move(finalizer));
    return scan;
}

} // namespace processor
} // namespace lbug
