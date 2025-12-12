#pragma once

#include "logical_operator_visitor.h"
#include "planner/operator/logical_plan.h"

namespace lbug {
namespace optimizer {

/* Due to the nature of graph pattern, a (node)-[rel]-(node) is always interpreted as two joins.
 * However, in many cases, a single join is sufficient.
 * E.g. MATCH (a)-[e]->(b) RETURN e.date
 * Our planner will generate a plan where the HJ is redundant.
 *      HJ
 *     /  \
 *   E(e) S(b)
 *    |
 *   S(a)
 * This optimizer prunes such redundant joins.
 */
class RemoveUnnecessaryJoinOptimizer : public LogicalOperatorVisitor {
public:
    void rewrite(planner::LogicalPlan* plan);

private:
    std::shared_ptr<planner::LogicalOperator> visitOperator(
        const std::shared_ptr<planner::LogicalOperator>& op);

    std::shared_ptr<planner::LogicalOperator> visitHashJoinReplace(
        std::shared_ptr<planner::LogicalOperator> op) override;
};

} // namespace optimizer
} // namespace lbug
