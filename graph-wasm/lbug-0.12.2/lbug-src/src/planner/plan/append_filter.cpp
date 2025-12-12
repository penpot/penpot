#include "planner/operator/logical_filter.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendFilters(const expression_vector& predicates, LogicalPlan& plan) {
    for (auto& predicate : predicates) {
        appendFilter(predicate, plan);
    }
}

void Planner::appendFilter(const std::shared_ptr<Expression>& predicate, LogicalPlan& plan) {
    planSubqueryIfNecessary(predicate, plan);
    auto filter = make_shared<LogicalFilter>(predicate, plan.getLastOperator());
    appendFlattens(filter->getGroupsPosToFlatten(), plan);
    filter->setChild(0, plan.getLastOperator());
    filter->computeFactorizedSchema();
    // estimate cardinality
    filter->setCardinality(
        cardinalityEstimator.estimateFilter(plan.getLastOperatorRef(), *predicate));
    plan.setLastOperator(std::move(filter));
}

} // namespace planner
} // namespace lbug
