#include "planner/operator/logical_empty_result.h"
#include "planner/planner.h"

namespace lbug {
namespace planner {

void Planner::appendEmptyResult(LogicalPlan& plan) {
    auto op = std::make_shared<LogicalEmptyResult>(*plan.getSchema());
    op->computeFactorizedSchema();
    plan.setLastOperator(std::move(op));
}

} // namespace planner
} // namespace lbug
