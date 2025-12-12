#pragma once

#include "logical_operator_visitor.h"

namespace lbug {
namespace optimizer {

class CorrelatedSubqueryUnnestSolver : public LogicalOperatorVisitor {
public:
    explicit CorrelatedSubqueryUnnestSolver(planner::LogicalOperator* accumulateOp)
        : accumulateOp{accumulateOp} {}
    void solve(planner::LogicalOperator* root_);

private:
    void visitOperator(planner::LogicalOperator* op);
    void visitExpressionsScan(planner::LogicalOperator* op) final;

    void solveAccHashJoin(planner::LogicalOperator* op) const;

private:
    planner::LogicalOperator* accumulateOp;
};

} // namespace optimizer
} // namespace lbug
