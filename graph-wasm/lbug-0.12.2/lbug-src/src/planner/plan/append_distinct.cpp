#include "planner/operator/logical_distinct.h"
#include "planner/planner.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendDistinct(const expression_vector& keys, LogicalPlan& plan) {
    KU_ASSERT(!keys.empty());
    auto distinct = make_shared<LogicalDistinct>(keys, plan.getLastOperator());
    appendFlattens(distinct->getGroupsPosToFlatten(), plan);
    distinct->setChild(0, plan.getLastOperator());
    distinct->computeFactorizedSchema();
    plan.setLastOperator(std::move(distinct));
}

} // namespace planner
} // namespace lbug
