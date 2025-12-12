#include "binder/expression_visitor.h"
#include "binder/query/return_with_clause/bound_projection_body.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::planProjectionBody(const BoundProjectionBody* projectionBody, LogicalPlan& plan) {
    auto expressionsToProject = projectionBody->getProjectionExpressions();
    if (expressionsToProject.empty()) {
        return;
    }
    if (plan.isEmpty()) { // e.g. RETURN 1, COUNT(2)
        appendDummyScan(plan);
    }
    auto expressionsToAggregate = projectionBody->getAggregateExpressions();
    auto expressionsToGroupBy = projectionBody->getGroupByExpressions();
    if (!expressionsToAggregate.empty()) {
        planAggregate(expressionsToAggregate, expressionsToGroupBy, plan);
    }
    // We might order by an expression that is not in projection list, so after order by we
    // always need to append a projection.
    // If distinct is presented in projection list, we need to first append project to evaluate the
    // list, then take the distinct.
    // Order by should always be the last operator (except for skip/limit) because other operators
    // will break the order.
    if (projectionBody->isDistinct() && projectionBody->hasOrderByExpressions()) {
        appendProjection(expressionsToProject, plan);
        appendDistinct(expressionsToProject, plan);
        planOrderBy(expressionsToProject, projectionBody->getOrderByExpressions(),
            projectionBody->getSortingOrders(), plan);
        appendProjection(expressionsToProject, plan);
    } else if (projectionBody->isDistinct()) {
        appendProjection(expressionsToProject, plan);
        appendDistinct(expressionsToProject, plan);
    } else if (projectionBody->hasOrderByExpressions()) {
        planOrderBy(expressionsToProject, projectionBody->getOrderByExpressions(),
            projectionBody->getSortingOrders(), plan);
        appendProjection(expressionsToProject, plan);
    } else {
        appendProjection(expressionsToProject, plan);
    }
    if (projectionBody->hasSkipOrLimit()) {
        appendMultiplicityReducer(plan);
        appendLimit(projectionBody->getSkipNumber(), projectionBody->getLimitNumber(), plan);
    }
}

void Planner::planAggregate(const expression_vector& expressionsToAggregate,
    const expression_vector& expressionsToGroupBy, LogicalPlan& plan) {
    KU_ASSERT(!expressionsToAggregate.empty());
    expression_vector expressionsToProject;
    for (auto& expressionToAggregate : expressionsToAggregate) {
        if (ExpressionChildrenCollector::collectChildren(*expressionToAggregate)
                .empty()) { // skip COUNT(*)
            continue;
        }
        expressionsToProject.push_back(expressionToAggregate->getChild(0));
    }
    for (auto& expressionToGroupBy : expressionsToGroupBy) {
        expressionsToProject.push_back(expressionToGroupBy);
    }
    appendProjection(expressionsToProject, plan);
    appendAggregate(expressionsToGroupBy, expressionsToAggregate, plan);
}

void Planner::planOrderBy(const binder::expression_vector& expressionsToProject,
    const binder::expression_vector& expressionsToOrderBy, const std::vector<bool>& isAscOrders,
    LogicalPlan& plan) {
    auto expressionsToProjectBeforeOrderBy = expressionsToProject;
    auto expressionsToProjectSet =
        expression_set{expressionsToProject.begin(), expressionsToProject.end()};
    for (auto& expression : expressionsToOrderBy) {
        if (!expressionsToProjectSet.contains(expression)) {
            expressionsToProjectBeforeOrderBy.push_back(expression);
        }
    }
    appendProjection(expressionsToProjectBeforeOrderBy, plan);
    appendOrderBy(expressionsToOrderBy, isAscOrders, plan);
}

} // namespace planner
} // namespace lbug
