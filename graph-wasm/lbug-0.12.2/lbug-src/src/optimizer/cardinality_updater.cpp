#include "optimizer/cardinality_updater.h"

#include "binder/expression/expression_util.h"
#include "planner/join_order/cardinality_estimator.h"
#include "planner/operator/extend/logical_extend.h"
#include "planner/operator/logical_aggregate.h"
#include "planner/operator/logical_filter.h"
#include "planner/operator/logical_flatten.h"
#include "planner/operator/logical_hash_join.h"
#include "planner/operator/logical_intersect.h"
#include "planner/operator/logical_limit.h"
#include "planner/operator/logical_plan.h"

namespace lbug::optimizer {
void CardinalityUpdater::rewrite(planner::LogicalPlan* plan) {
    visitOperator(plan->getLastOperator().get());
}

void CardinalityUpdater::visitOperator(planner::LogicalOperator* op) {
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        visitOperator(op->getChild(i).get());
    }
    visitOperatorSwitchWithDefault(op);
}

void CardinalityUpdater::visitOperatorSwitchWithDefault(planner::LogicalOperator* op) {
    switch (op->getOperatorType()) {
    case planner::LogicalOperatorType::SCAN_NODE_TABLE: {
        visitScanNodeTable(op);
        break;
    }
    case planner::LogicalOperatorType::EXTEND: {
        visitExtend(op);
        break;
    }
    case planner::LogicalOperatorType::HASH_JOIN: {
        visitHashJoin(op);
        break;
    }
    case planner::LogicalOperatorType::CROSS_PRODUCT: {
        visitCrossProduct(op);
        break;
    }
    case planner::LogicalOperatorType::INTERSECT: {
        visitIntersect(op);
        break;
    }
    case planner::LogicalOperatorType::FLATTEN: {
        visitFlatten(op);
        break;
    }
    case planner::LogicalOperatorType::FILTER: {
        visitFilter(op);
        break;
    }
    case planner::LogicalOperatorType::LIMIT: {
        visitLimit(op);
        break;
    }
    case planner::LogicalOperatorType::AGGREGATE: {
        visitAggregate(op);
        break;
    }
    default: {
        visitOperatorDefault(op);
        break;
    }
    }
}

void CardinalityUpdater::visitOperatorDefault(planner::LogicalOperator* op) {
    if (op->getNumChildren() == 1) {
        op->setCardinality(op->getChild(0)->getCardinality());
    }
}

void CardinalityUpdater::visitScanNodeTable(planner::LogicalOperator* op) {
    op->setCardinality(cardinalityEstimator.estimateScanNode(*op));
}

void CardinalityUpdater::visitExtend(planner::LogicalOperator* op) {
    KU_ASSERT(transaction);
    auto& extend = op->cast<planner::LogicalExtend&>();
    const auto extensionRate = cardinalityEstimator.getExtensionRate(*extend.getRel(),
        *extend.getBoundNode(), transaction);
    extend.setCardinality(
        cardinalityEstimator.multiply(extensionRate, op->getChild(0)->getCardinality()));
}

void CardinalityUpdater::visitHashJoin(planner::LogicalOperator* op) {
    auto& hashJoin = op->cast<planner::LogicalHashJoin&>();
    KU_ASSERT(hashJoin.getNumChildren() >= 2);
    hashJoin.setCardinality(cardinalityEstimator.estimateHashJoin(hashJoin.getJoinConditions(),
        *hashJoin.getChild(0), *hashJoin.getChild(1)));
}

void CardinalityUpdater::visitCrossProduct(planner::LogicalOperator* op) {
    op->setCardinality(
        cardinalityEstimator.estimateCrossProduct(*op->getChild(0), *op->getChild(1)));
}

void CardinalityUpdater::visitIntersect(planner::LogicalOperator* op) {
    auto& intersect = op->cast<planner::LogicalIntersect&>();
    KU_ASSERT(intersect.getNumChildren() >= 2);
    std::vector<planner::LogicalOperator*> buildOps;
    for (uint32_t i = 1; i < intersect.getNumChildren(); ++i) {
        buildOps.push_back(intersect.getChild(i).get());
    }
    intersect.setCardinality(cardinalityEstimator.estimateIntersect(intersect.getKeyNodeIDs(),
        *intersect.getChild(0), buildOps));
}

void CardinalityUpdater::visitFlatten(planner::LogicalOperator* op) {
    auto& flatten = op->cast<planner::LogicalFlatten&>();
    flatten.setCardinality(
        cardinalityEstimator.estimateFlatten(*flatten.getChild(0), flatten.getGroupPos()));
}

void CardinalityUpdater::visitFilter(planner::LogicalOperator* op) {
    auto& filter = op->cast<planner::LogicalFilter&>();
    filter.setCardinality(
        cardinalityEstimator.estimateFilter(*filter.getChild(0), *filter.getPredicate()));
}

void CardinalityUpdater::visitLimit(planner::LogicalOperator* op) {
    auto& limit = op->cast<planner::LogicalLimit&>();
    if (limit.hasLimitNum() && binder::ExpressionUtil::canEvaluateAsLiteral(*limit.getLimitNum())) {
        limit.setCardinality(binder::ExpressionUtil::evaluateAsSkipLimit(*limit.getLimitNum()));
    }
}

void CardinalityUpdater::visitAggregate(planner::LogicalOperator* op) {
    auto& aggregate = op->cast<planner::LogicalAggregate&>();
    aggregate.setCardinality(cardinalityEstimator.estimateAggregate(aggregate));
}

} // namespace lbug::optimizer
