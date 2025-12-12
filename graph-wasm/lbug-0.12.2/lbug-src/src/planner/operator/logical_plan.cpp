#include "planner/operator/logical_plan.h"

#include "planner/operator/logical_explain.h"

namespace lbug {
namespace planner {

bool LogicalPlan::isProfile() const {
    return lastOperator->getOperatorType() == LogicalOperatorType::EXPLAIN &&
           reinterpret_cast<LogicalExplain*>(lastOperator.get())->getExplainType() ==
               common::ExplainType::PROFILE;
}

bool LogicalPlan::hasUpdate() const {
    return lastOperator->hasUpdateRecursive();
}

} // namespace planner
} // namespace lbug
