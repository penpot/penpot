#pragma once

#include "logical_operator_visitor.h"
#include "planner/operator/logical_plan.h"

namespace lbug {
namespace optimizer {

// This optimizer enables the Accumulated hash join algorithm as introduced in paper "Lbug Graph
// Database Management System".
class HashJoinSIPOptimizer final : public LogicalOperatorVisitor {
public:
    void rewrite(const planner::LogicalPlan* plan);

private:
    void visitOperator(planner::LogicalOperator* op);

    void visitHashJoin(planner::LogicalOperator* op) override;

    void visitIntersect(planner::LogicalOperator* op) override;

    void visitPathPropertyProbe(planner::LogicalOperator* op) override;
};

} // namespace optimizer
} // namespace lbug
