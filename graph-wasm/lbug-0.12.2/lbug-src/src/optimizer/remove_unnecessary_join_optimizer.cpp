#include "optimizer/remove_unnecessary_join_optimizer.h"

#include "planner/operator/logical_hash_join.h"
#include "planner/operator/scan/logical_scan_node_table.h"

using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace optimizer {

void RemoveUnnecessaryJoinOptimizer::rewrite(LogicalPlan* plan) {
    visitOperator(plan->getLastOperator());
}

std::shared_ptr<LogicalOperator> RemoveUnnecessaryJoinOptimizer::visitOperator(
    const std::shared_ptr<LogicalOperator>& op) {
    // bottom-up traversal
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        op->setChild(i, visitOperator(op->getChild(i)));
    }
    auto result = visitOperatorReplaceSwitch(op);
    result->computeFlatSchema();
    return result;
}

std::shared_ptr<LogicalOperator> RemoveUnnecessaryJoinOptimizer::visitHashJoinReplace(
    std::shared_ptr<LogicalOperator> op) {
    auto hashJoin = (LogicalHashJoin*)op.get();
    switch (hashJoin->getJoinType()) {
    case JoinType::MARK:
    case JoinType::LEFT: {
        // Do not prune no-trivial join type
        return op;
    }
    default:
        break;
    }
    // TODO(Xiyang): Double check on these changes here.
    if (op->getChild(1)->getOperatorType() == LogicalOperatorType::SCAN_NODE_TABLE) {
        const auto scanNode = ku_dynamic_cast<LogicalScanNodeTable*>(op->getChild(1).get());
        if (scanNode->getProperties().empty()) {
            // Build side is trivial. Prune build side.
            return op->getChild(0);
        }
    }
    if (op->getChild(0)->getOperatorType() == LogicalOperatorType::SCAN_NODE_TABLE) {
        const auto scanNode = ku_dynamic_cast<LogicalScanNodeTable*>(op->getChild(0).get());
        if (scanNode->getProperties().empty()) {
            // Probe side is trivial. Prune probe side.
            return op->getChild(1);
        }
    }
    return op;
}

} // namespace optimizer
} // namespace lbug
