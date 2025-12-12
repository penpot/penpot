#pragma once

#include "logical_operator_visitor.h"
#include "planner/operator/logical_plan.h"

namespace lbug {
namespace optimizer {

class FactorizationRewriter final : public LogicalOperatorVisitor {
public:
    void rewrite(planner::LogicalPlan* plan);

    void visitOperator(planner::LogicalOperator* op);

private:
    void visitHashJoin(planner::LogicalOperator* op) override;
    void visitIntersect(planner::LogicalOperator* op) override;
    void visitProjection(planner::LogicalOperator* op) override;
    void visitAccumulate(planner::LogicalOperator* op) override;
    void visitAggregate(planner::LogicalOperator* op) override;
    void visitOrderBy(planner::LogicalOperator* op) override;
    void visitLimit(planner::LogicalOperator* op) override;
    void visitDistinct(planner::LogicalOperator* op) override;
    void visitUnwind(planner::LogicalOperator* op) override;
    void visitUnion(planner::LogicalOperator* op) override;
    void visitFilter(planner::LogicalOperator* op) override;
    void visitSetProperty(planner::LogicalOperator* op) override;
    void visitDelete(planner::LogicalOperator* op) override;
    void visitInsert(planner::LogicalOperator* op) override;
    void visitMerge(planner::LogicalOperator* op) override;
    void visitCopyTo(planner::LogicalOperator* op) override;

    std::shared_ptr<planner::LogicalOperator> appendFlattens(
        std::shared_ptr<planner::LogicalOperator> op,
        const std::unordered_set<planner::f_group_pos>& groupsPos);
    std::shared_ptr<planner::LogicalOperator> appendFlattenIfNecessary(
        std::shared_ptr<planner::LogicalOperator> op, planner::f_group_pos groupPos);
};

} // namespace optimizer
} // namespace lbug
