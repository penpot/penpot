#include "planner/operator/logical_aggregate.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendAggregate(const expression_vector& expressionsToGroupBy,
    const expression_vector& expressionsToAggregate, LogicalPlan& plan) {
    auto aggregate = make_shared<LogicalAggregate>(expressionsToGroupBy, expressionsToAggregate,
        plan.getLastOperator());
    appendFlattens(aggregate->getGroupsPosToFlatten(), plan);
    aggregate->setChild(0, plan.getLastOperator());
    aggregate->computeFactorizedSchema();
    aggregate->setCardinality(cardinalityEstimator.estimateAggregate(*aggregate));
    plan.setLastOperator(std::move(aggregate));
}

} // namespace planner
} // namespace lbug
