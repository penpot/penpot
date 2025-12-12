#pragma once

#include "logical_operator_visitor.h"
#include "planner/operator/logical_plan.h"

namespace lbug {
namespace optimizer {

// This optimizer analyzes the dependency between group by keys. If key2 depends on key1 (e.g. key1
// is a primary key column) we only hash on key1 and saves key2 as a payload.
class AggKeyDependencyOptimizer : public LogicalOperatorVisitor {
public:
    void rewrite(planner::LogicalPlan* plan);

private:
    void visitOperator(planner::LogicalOperator* op);

    void visitAggregate(planner::LogicalOperator* op) override;
    void visitDistinct(planner::LogicalOperator* op) override;

    std::pair<binder::expression_vector, binder::expression_vector> resolveKeysAndDependentKeys(
        const binder::expression_vector& keys);
};

} // namespace optimizer
} // namespace lbug
