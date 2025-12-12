#pragma once

#include "common/enums/path_semantic.h"
#include "logical_operator_visitor.h"
#include "planner/operator/logical_plan.h"

namespace lbug {
namespace main {
class ClientContext;
}
namespace binder {
struct BoundSetPropertyInfo;
}
namespace planner {
struct LogicalInsertInfo;
}
namespace optimizer {

// ProjectionPushDownOptimizer implements the logic to avoid materializing unnecessary properties
// for hash join build.
// Note the optimization is for properties & variables only but not for general expressions. This is
// because it's hard to figure out what expression is in-use, e.g. COUNT(a.age) + 1, it could be
// either the whole expression was evaluated in a WITH clause or only COUNT(a.age) was evaluated or
// only a.age is evaluate. For simplicity, we only consider the push down for property.
class ProjectionPushDownOptimizer : public LogicalOperatorVisitor {
public:
    void rewrite(planner::LogicalPlan* plan);
    explicit ProjectionPushDownOptimizer(common::PathSemantic semantic) : semantic(semantic){};

private:
    void visitOperator(planner::LogicalOperator* op);

    void visitPathPropertyProbe(planner::LogicalOperator* op) override;
    void visitExtend(planner::LogicalOperator* op) override;
    void visitAccumulate(planner::LogicalOperator* op) override;
    void visitFilter(planner::LogicalOperator* op) override;
    void visitNodeLabelFilter(planner::LogicalOperator* op) override;
    void visitHashJoin(planner::LogicalOperator* op) override;
    void visitIntersect(planner::LogicalOperator* op) override;
    void visitProjection(planner::LogicalOperator* op) override;
    void visitOrderBy(planner::LogicalOperator* op) override;
    void visitUnwind(planner::LogicalOperator* op) override;
    void visitSetProperty(planner::LogicalOperator* op) override;
    void visitInsert(planner::LogicalOperator* op) override;
    void visitDelete(planner::LogicalOperator* op) override;
    void visitMerge(planner::LogicalOperator* op) override;
    void visitCopyFrom(planner::LogicalOperator* op) override;
    void visitTableFunctionCall(planner::LogicalOperator*) override;

    void visitSetInfo(const binder::BoundSetPropertyInfo& info);
    void visitInsertInfo(const planner::LogicalInsertInfo& info);

    void collectExpressionsInUse(std::shared_ptr<binder::Expression> expression);

    binder::expression_vector pruneExpressions(const binder::expression_vector& expressions);

    void preAppendProjection(planner::LogicalOperator* op, common::idx_t childIdx,
        binder::expression_vector expressions);

private:
    binder::expression_set propertiesInUse;
    binder::expression_set variablesInUse;
    binder::expression_set nodeOrRelInUse;
    common::PathSemantic semantic;
};

} // namespace optimizer
} // namespace lbug
