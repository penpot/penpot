#include "optimizer/top_k_optimizer.h"

#include "planner/operator/logical_limit.h"
#include "planner/operator/logical_order_by.h"

using namespace lbug::planner;
using namespace lbug::common;

namespace lbug {
namespace optimizer {

void TopKOptimizer::rewrite(planner::LogicalPlan* plan) {
    plan->setLastOperator(visitOperator(plan->getLastOperator()));
}

std::shared_ptr<LogicalOperator> TopKOptimizer::visitOperator(
    const std::shared_ptr<LogicalOperator>& op) {
    // bottom-up traversal
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        op->setChild(i, visitOperator(op->getChild(i)));
    }
    auto result = visitOperatorReplaceSwitch(op);
    result->computeFlatSchema();
    return result;
}

// TODO(Xiyang): we should probably remove the projection between ORDER BY and MULTIPLICITY REDUCER
// We search for pattern
// ORDER BY -> PROJECTION -> MULTIPLICITY REDUCER -> LIMIT
// ORDER BY -> MULTIPLICITY REDUCER -> LIMIT
// and rewrite as TOP_K
std::shared_ptr<LogicalOperator> TopKOptimizer::visitLimitReplace(
    std::shared_ptr<LogicalOperator> op) {
    auto limit = op->ptrCast<LogicalLimit>();
    if (!limit->hasLimitNum()) {
        return op; // only skip no limit. No need to rewrite
    }
    auto multiplicityReducer = limit->getChild(0);
    KU_ASSERT(multiplicityReducer->getOperatorType() == LogicalOperatorType::MULTIPLICITY_REDUCER);
    auto projectionOrOrderBy = multiplicityReducer->getChild(0);
    std::shared_ptr<LogicalOrderBy> orderBy;
    if (projectionOrOrderBy->getOperatorType() == LogicalOperatorType::PROJECTION) {
        if (projectionOrOrderBy->getChild(0)->getOperatorType() != LogicalOperatorType::ORDER_BY) {
            return op;
        }
        orderBy = std::static_pointer_cast<LogicalOrderBy>(projectionOrOrderBy->getChild(0));
    } else if (projectionOrOrderBy->getOperatorType() == LogicalOperatorType::ORDER_BY) {
        orderBy = std::static_pointer_cast<LogicalOrderBy>(projectionOrOrderBy);
    } else {
        return op;
    }
    KU_ASSERT(orderBy != nullptr);
    if (limit->hasLimitNum()) {
        orderBy->setLimitNum(limit->getLimitNum());
    }
    if (limit->hasSkipNum()) {
        orderBy->setSkipNum(limit->getSkipNum());
    }
    return projectionOrOrderBy;
}

} // namespace optimizer
} // namespace lbug
