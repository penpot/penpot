#include "optimizer/factorization_rewriter.h"

#include "binder/expression_visitor.h"
#include "planner/operator/factorization/flatten_resolver.h"
#include "planner/operator/logical_accumulate.h"
#include "planner/operator/logical_aggregate.h"
#include "planner/operator/logical_distinct.h"
#include "planner/operator/logical_filter.h"
#include "planner/operator/logical_flatten.h"
#include "planner/operator/logical_hash_join.h"
#include "planner/operator/logical_intersect.h"
#include "planner/operator/logical_limit.h"
#include "planner/operator/logical_order_by.h"
#include "planner/operator/logical_projection.h"
#include "planner/operator/logical_union.h"
#include "planner/operator/logical_unwind.h"
#include "planner/operator/persistent/logical_copy_to.h"
#include "planner/operator/persistent/logical_delete.h"
#include "planner/operator/persistent/logical_insert.h"
#include "planner/operator/persistent/logical_merge.h"
#include "planner/operator/persistent/logical_set.h"

using namespace lbug::common;
using namespace lbug::binder;
using namespace lbug::planner;

namespace lbug {
namespace optimizer {

void FactorizationRewriter::rewrite(planner::LogicalPlan* plan) {
    visitOperator(plan->getLastOperator().get());
}

void FactorizationRewriter::visitOperator(planner::LogicalOperator* op) {
    // bottom-up traversal
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        visitOperator(op->getChild(i).get());
    }
    visitOperatorSwitch(op);
    op->computeFactorizedSchema();
}

void FactorizationRewriter::visitHashJoin(planner::LogicalOperator* op) {
    // TODO(Royi) correctly set the cardinality here
    auto& hashJoin = op->cast<LogicalHashJoin>();
    auto groupsPosToFlattenOnProbeSide = hashJoin.getGroupsPosToFlattenOnProbeSide();
    hashJoin.setChild(0, appendFlattens(hashJoin.getChild(0), groupsPosToFlattenOnProbeSide));
    auto groupsPosToFlattenOnBuildSide = hashJoin.getGroupsPosToFlattenOnBuildSide();
    hashJoin.setChild(1, appendFlattens(hashJoin.getChild(1), groupsPosToFlattenOnBuildSide));
}

void FactorizationRewriter::visitIntersect(planner::LogicalOperator* op) {
    auto& intersect = op->cast<LogicalIntersect>();
    auto groupsPosToFlattenOnProbeSide = intersect.getGroupsPosToFlattenOnProbeSide();
    intersect.setChild(0, appendFlattens(intersect.getChild(0), groupsPosToFlattenOnProbeSide));
    for (auto i = 0u; i < intersect.getNumBuilds(); ++i) {
        auto groupPosToFlatten = intersect.getGroupsPosToFlattenOnBuildSide(i);
        auto childIdx = i + 1; // skip probe
        intersect.setChild(childIdx,
            appendFlattens(intersect.getChild(childIdx), groupPosToFlatten));
    }
}

void FactorizationRewriter::visitProjection(planner::LogicalOperator* op) {
    auto& projection = op->cast<LogicalProjection>();
    bool hasRandomFunction = false;
    for (auto& expr : projection.getExpressionsToProject()) {
        if (ExpressionVisitor::isRandom(*expr)) {
            hasRandomFunction = true;
        }
    }
    if (hasRandomFunction) {
        // Fall back to tuple-at-a-time evaluation.
        auto groupsPos = op->getChild(0)->getSchema()->getGroupsPosInScope();
        auto groupsPosToFlatten =
            FlattenAll::getGroupsPosToFlatten(groupsPos, *op->getChild(0)->getSchema());
        projection.setChild(0, appendFlattens(projection.getChild(0), groupsPosToFlatten));
    } else {
        for (auto& expression : projection.getExpressionsToProject()) {
            auto groupsPosToFlatten =
                FlattenAllButOne::getGroupsPosToFlatten(expression, *op->getChild(0)->getSchema());
            projection.setChild(0, appendFlattens(projection.getChild(0), groupsPosToFlatten));
        }
    }
}

void FactorizationRewriter::visitAccumulate(planner::LogicalOperator* op) {
    auto& accumulate = op->cast<LogicalAccumulate>();
    auto groupsPosToFlatten = accumulate.getGroupPositionsToFlatten();
    accumulate.setChild(0, appendFlattens(accumulate.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitAggregate(planner::LogicalOperator* op) {
    auto& aggregate = op->cast<LogicalAggregate>();
    auto groupsPosToFlatten = aggregate.getGroupsPosToFlatten();
    aggregate.setChild(0, appendFlattens(aggregate.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitOrderBy(planner::LogicalOperator* op) {
    auto& orderBy = op->cast<LogicalOrderBy>();
    auto groupsPosToFlatten = orderBy.getGroupsPosToFlatten();
    orderBy.setChild(0, appendFlattens(orderBy.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitLimit(planner::LogicalOperator* op) {
    auto& limit = op->cast<LogicalLimit>();
    auto groupsPosToFlatten = limit.getGroupsPosToFlatten();
    limit.setChild(0, appendFlattens(limit.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitDistinct(planner::LogicalOperator* op) {
    auto& distinct = op->cast<LogicalDistinct>();
    auto groupsPosToFlatten = distinct.getGroupsPosToFlatten();
    distinct.setChild(0, appendFlattens(distinct.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitUnwind(planner::LogicalOperator* op) {
    auto& unwind = op->cast<LogicalUnwind>();
    auto groupsPosToFlatten = unwind.getGroupsPosToFlatten();
    unwind.setChild(0, appendFlattens(unwind.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitUnion(planner::LogicalOperator* op) {
    auto& union_ = op->cast<LogicalUnion>();
    for (auto i = 0u; i < union_.getNumChildren(); ++i) {
        auto groupsPosToFlatten = union_.getGroupsPosToFlatten(i);
        union_.setChild(i, appendFlattens(union_.getChild(i), groupsPosToFlatten));
    }
}

void FactorizationRewriter::visitFilter(planner::LogicalOperator* op) {
    auto& filter = op->cast<LogicalFilter>();
    auto groupsPosToFlatten = filter.getGroupsPosToFlatten();
    filter.setChild(0, appendFlattens(filter.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitSetProperty(planner::LogicalOperator* op) {
    auto& set = op->cast<LogicalSetProperty>();
    for (auto i = 0u; i < set.getInfos().size(); ++i) {
        auto groupsPos = set.getGroupsPosToFlatten(i);
        set.setChild(0, appendFlattens(set.getChild(0), groupsPos));
    }
}

void FactorizationRewriter::visitDelete(planner::LogicalOperator* op) {
    auto& delete_ = op->cast<LogicalDelete>();
    auto groupsPosToFlatten = delete_.getGroupsPosToFlatten();
    delete_.setChild(0, appendFlattens(delete_.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitInsert(planner::LogicalOperator* op) {
    auto& insert = op->cast<LogicalInsert>();
    auto groupsPosToFlatten = insert.getGroupsPosToFlatten();
    insert.setChild(0, appendFlattens(insert.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitMerge(planner::LogicalOperator* op) {
    auto& merge = op->cast<LogicalMerge>();
    auto groupsPosToFlatten = merge.getGroupsPosToFlatten();
    merge.setChild(0, appendFlattens(merge.getChild(0), groupsPosToFlatten));
}

void FactorizationRewriter::visitCopyTo(planner::LogicalOperator* op) {
    auto& copyTo = op->cast<LogicalCopyTo>();
    auto groupsPosToFlatten = copyTo.getGroupsPosToFlatten();
    copyTo.setChild(0, appendFlattens(copyTo.getChild(0), groupsPosToFlatten));
}

std::shared_ptr<planner::LogicalOperator> FactorizationRewriter::appendFlattens(
    std::shared_ptr<planner::LogicalOperator> op,
    const std::unordered_set<f_group_pos>& groupsPos) {
    auto currentChild = std::move(op);
    for (auto groupPos : groupsPos) {
        currentChild = appendFlattenIfNecessary(std::move(currentChild), groupPos);
    }
    return currentChild;
}

std::shared_ptr<planner::LogicalOperator> FactorizationRewriter::appendFlattenIfNecessary(
    std::shared_ptr<planner::LogicalOperator> op, planner::f_group_pos groupPos) {
    if (op->getSchema()->getGroup(groupPos)->isFlat()) {
        return op;
    }
    // we set the cardinalities in a separate pass
    auto flatten = std::make_shared<LogicalFlatten>(groupPos, std::move(op), 0 /* cardinality */);
    flatten->computeFactorizedSchema();
    return flatten;
}

} // namespace optimizer
} // namespace lbug
