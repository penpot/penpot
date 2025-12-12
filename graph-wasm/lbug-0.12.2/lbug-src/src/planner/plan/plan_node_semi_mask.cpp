#include "binder/expression/expression_util.h"
#include "binder/expression/property_expression.h"
#include "binder/expression_visitor.h"
#include "planner/operator/logical_dummy_sink.h"
#include "planner/operator/sip/logical_semi_masker.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

void Planner::appendNodeSemiMask(SemiMaskTargetType targetType, const NodeExpression& node,
    LogicalPlan& plan) {
    auto semiMasker = std::make_shared<LogicalSemiMasker>(SemiMaskKeyType::NODE, targetType,
        node.getInternalID(), node.getTableIDs(), plan.getLastOperator());
    semiMasker->computeFactorizedSchema();
    plan.setLastOperator(semiMasker);
}

void Planner::appendDummySink(LogicalPlan& plan) {
    auto dummySink = std::make_shared<LogicalDummySink>(plan.getLastOperator());
    dummySink->computeFactorizedSchema();
    plan.setLastOperator(std::move(dummySink));
}

// Create a plan with a root semi masker for given node and node predicate.
LogicalPlan Planner::getNodeSemiMaskPlan(SemiMaskTargetType targetType, const NodeExpression& node,
    std::shared_ptr<Expression> nodePredicate) {
    auto plan = LogicalPlan();
    auto prevCollection = enterNewPropertyExprCollection();
    auto collector = PropertyExprCollector();
    collector.visit(nodePredicate);
    for (auto& expr : ExpressionUtil::removeDuplication(collector.getPropertyExprs())) {
        auto& propExpr = expr->constCast<PropertyExpression>();
        propertyExprCollection.addProperties(propExpr.getVariableName(), expr);
    }
    appendScanNodeTable(node.getInternalID(), node.getTableIDs(), getProperties(node), plan);
    appendFilter(nodePredicate, plan);
    exitPropertyExprCollection(std::move(prevCollection));
    appendNodeSemiMask(targetType, node, plan);
    appendDummySink(plan);
    return plan;
}

} // namespace planner
} // namespace lbug
