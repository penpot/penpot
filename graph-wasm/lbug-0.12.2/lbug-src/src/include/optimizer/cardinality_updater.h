#pragma once

#include "optimizer/logical_operator_visitor.h"
namespace lbug {
namespace planner {
class LogicalPlan;
class CardinalityEstimator;
} // namespace planner

namespace transaction {
class Transaction;
}

namespace optimizer {
class CardinalityUpdater : public LogicalOperatorVisitor {
public:
    explicit CardinalityUpdater(const planner::CardinalityEstimator& cardinalityEstimator,
        const transaction::Transaction* transaction)
        : cardinalityEstimator(cardinalityEstimator), transaction(transaction) {}

    void rewrite(planner::LogicalPlan* plan);

private:
    void visitOperator(planner::LogicalOperator* op);
    void visitOperatorSwitchWithDefault(planner::LogicalOperator* op);

    void visitOperatorDefault(planner::LogicalOperator* op);
    void visitScanNodeTable(planner::LogicalOperator* op) override;
    void visitExtend(planner::LogicalOperator* op) override;
    void visitHashJoin(planner::LogicalOperator* op) override;
    void visitCrossProduct(planner::LogicalOperator* op) override;
    void visitIntersect(planner::LogicalOperator* op) override;
    void visitFlatten(planner::LogicalOperator* op) override;
    void visitFilter(planner::LogicalOperator* op) override;
    void visitAggregate(planner::LogicalOperator* op) override;
    void visitLimit(planner::LogicalOperator* op) override;

    const planner::CardinalityEstimator& cardinalityEstimator;
    const transaction::Transaction* transaction;
};
} // namespace optimizer
} // namespace lbug
