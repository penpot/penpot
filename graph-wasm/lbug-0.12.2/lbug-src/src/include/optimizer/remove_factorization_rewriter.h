#pragma once

#include "logical_operator_visitor.h"
#include "planner/operator/logical_plan.h"

namespace lbug {
namespace optimizer {

class RemoveFactorizationRewriter : public LogicalOperatorVisitor {
public:
    void rewrite(planner::LogicalPlan* plan);

    std::shared_ptr<planner::LogicalOperator> visitOperator(
        const std::shared_ptr<planner::LogicalOperator>& op);

private:
    std::shared_ptr<planner::LogicalOperator> visitFlattenReplace(
        std::shared_ptr<planner::LogicalOperator> op) override;
};

} // namespace optimizer
} // namespace lbug
