#include "optimizer/correlated_subquery_unnest_solver.h"

#include "common/exception/internal.h"
#include "planner/operator/logical_hash_join.h"
#include "planner/operator/scan/logical_expressions_scan.h"

using namespace lbug::planner;

namespace lbug {
namespace optimizer {

void CorrelatedSubqueryUnnestSolver::solve(planner::LogicalOperator* root_) {
    visitOperator(root_);
}

void CorrelatedSubqueryUnnestSolver::visitOperator(LogicalOperator* op) {
    visitOperatorSwitch(op);
    if (LogicalOperatorUtils::isAccHashJoin(*op)) {
        solveAccHashJoin(op);
        return;
    }
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        visitOperator(op->getChild(i).get());
    }
}

void CorrelatedSubqueryUnnestSolver::solveAccHashJoin(LogicalOperator* op) const {
    auto& hashJoin = op->cast<LogicalHashJoin>();
    auto& sipInfo = hashJoin.getSIPInfoUnsafe();
    sipInfo.dependency = SIPDependency::BUILD_DEPENDS_ON_PROBE;
    sipInfo.direction = SIPDirection::PROBE_TO_BUILD;
    auto acc = op->getChild(0).get();
    auto rightSolver = std::make_unique<CorrelatedSubqueryUnnestSolver>(acc);
    rightSolver->solve(hashJoin.getChild(1).get());
    auto leftSolver = std::make_unique<CorrelatedSubqueryUnnestSolver>(accumulateOp);
    leftSolver->solve(acc->getChild(0).get());
}

void CorrelatedSubqueryUnnestSolver::visitExpressionsScan(LogicalOperator* op) {
    auto expressionsScan = op->ptrCast<LogicalExpressionsScan>();
    // LCOV_EXCL_START
    if (accumulateOp == nullptr) {
        throw common::InternalException(
            "Failed to execute CorrelatedSubqueryUnnestSolver. This should not happen.");
    }
    // LCOV_EXCL_STOP
    expressionsScan->setOuterAccumulate(accumulateOp);
}

} // namespace optimizer
} // namespace lbug
