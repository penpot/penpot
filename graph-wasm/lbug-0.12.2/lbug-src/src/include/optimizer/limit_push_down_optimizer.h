#pragma once

#include "planner/operator/logical_plan.h"

namespace lbug {
namespace optimizer {

class LimitPushDownOptimizer {
public:
    LimitPushDownOptimizer() : skipNumber{0}, limitNumber{common::INVALID_LIMIT} {}

    void rewrite(planner::LogicalPlan* plan);

private:
    void visitOperator(planner::LogicalOperator* op);

private:
    common::offset_t skipNumber;
    common::offset_t limitNumber;
};

} // namespace optimizer
} // namespace lbug
