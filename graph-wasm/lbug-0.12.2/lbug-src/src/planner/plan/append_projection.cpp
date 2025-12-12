#include "binder/expression_visitor.h"
#include "planner/operator/factorization/flatten_resolver.h"
#include "planner/operator/logical_projection.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendProjection(const expression_vector& expressionsToProject, LogicalPlan& plan) {
    for (auto& expression : expressionsToProject) {
        planSubqueryIfNecessary(expression, plan);
    }
    bool hasRandomFunction = false;
    for (auto& expr : expressionsToProject) {
        if (ExpressionVisitor::isRandom(*expr)) {
            hasRandomFunction = true;
        }
    }
    if (hasRandomFunction) {
        // Fall back to tuple-at-a-time evaluation.
        appendMultiplicityReducer(plan);
        appendFlattens(plan.getSchema()->getGroupsPosInScope(), plan);
    } else {
        for (auto& expression : expressionsToProject) {
            auto groupsPosToFlatten =
                FlattenAllButOne::getGroupsPosToFlatten(expression, *plan.getSchema());
            appendFlattens(groupsPosToFlatten, plan);
        }
    }
    auto projection = make_shared<LogicalProjection>(expressionsToProject, plan.getLastOperator());
    projection->computeFactorizedSchema();
    plan.setLastOperator(std::move(projection));
}

} // namespace planner
} // namespace lbug
