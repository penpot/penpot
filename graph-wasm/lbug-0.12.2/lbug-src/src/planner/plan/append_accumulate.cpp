#include "planner/operator/logical_accumulate.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

void Planner::tryAppendAccumulate(LogicalPlan& plan) {
    if (plan.getLastOperator()->getOperatorType() == LogicalOperatorType::ACCUMULATE) {
        return;
    }
    appendAccumulate(plan);
}

void Planner::appendAccumulate(LogicalPlan& plan) {
    appendAccumulate(AccumulateType::REGULAR, expression_vector{}, nullptr /* mark */, plan);
}

void Planner::appendOptionalAccumulate(std::shared_ptr<Expression> mark, LogicalPlan& plan) {
    appendAccumulate(AccumulateType::OPTIONAL_, expression_vector{}, mark, plan);
}

void Planner::appendAccumulate(const expression_vector& flatExprs, LogicalPlan& plan) {
    appendAccumulate(AccumulateType::REGULAR, flatExprs, nullptr /* mark */, plan);
}

void Planner::appendAccumulate(AccumulateType accumulateType, const expression_vector& flatExprs,
    std::shared_ptr<Expression> mark, LogicalPlan& plan) {
    auto op =
        make_shared<LogicalAccumulate>(accumulateType, flatExprs, mark, plan.getLastOperator());
    appendFlattens(op->getGroupPositionsToFlatten(), plan);
    op->setChild(0, plan.getLastOperator());
    op->computeFactorizedSchema();
    plan.setLastOperator(std::move(op));
}

} // namespace planner
} // namespace lbug
