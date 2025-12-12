#include "optimizer/remove_factorization_rewriter.h"

#include "common/exception/internal.h"
#include "optimizer/logical_operator_collector.h"

using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace optimizer {

void RemoveFactorizationRewriter::rewrite(planner::LogicalPlan* plan) {
    auto root = plan->getLastOperator();
    visitOperator(root);
    auto collector = LogicalFlattenCollector();
    collector.collect(root.get());
    if (collector.hasOperators()) {
        throw InternalException("Remove factorization rewriter failed.");
    }
}

std::shared_ptr<planner::LogicalOperator> RemoveFactorizationRewriter::visitOperator(
    const std::shared_ptr<planner::LogicalOperator>& op) {
    // bottom-up traversal
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        op->setChild(i, visitOperator(op->getChild(i)));
    }
    auto result = visitOperatorReplaceSwitch(op);
    result->computeFlatSchema();
    return result;
}

std::shared_ptr<planner::LogicalOperator> RemoveFactorizationRewriter::visitFlattenReplace(
    std::shared_ptr<planner::LogicalOperator> op) {
    return op->getChild(0);
}

} // namespace optimizer
} // namespace lbug
