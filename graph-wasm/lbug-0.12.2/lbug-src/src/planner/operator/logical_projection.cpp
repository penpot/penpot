#include "planner/operator/logical_projection.h"

#include "planner/operator/factorization/flatten_resolver.h"

namespace lbug {
namespace planner {

void LogicalProjection::computeFactorizedSchema() {
    auto childSchema = children[0]->getSchema();
    schema = childSchema->copy();
    schema->clearExpressionsInScope();
    for (auto& expression : expressions) {
        auto groupPos = INVALID_F_GROUP_POS;
        if (childSchema->isExpressionInScope(*expression)) { // expression to reference
            groupPos = childSchema->getGroupPos(*expression);
            schema->insertToScopeMayRepeat(expression, groupPos);
        } else { // expression to evaluate
            auto analyzer = GroupDependencyAnalyzer(false, *childSchema);
            analyzer.visit(expression);
            auto dependentGroupPos = analyzer.getDependentGroups();
            SchemaUtils::validateAtMostOneUnFlatGroup(dependentGroupPos, *childSchema);
            if (dependentGroupPos.empty()) { // constant
                groupPos = schema->createGroup();
                schema->setGroupAsSingleState(groupPos);
            } else {
                groupPos = SchemaUtils::getLeadingGroupPos(dependentGroupPos, *childSchema);
            }
            schema->insertToGroupAndScopeMayRepeat(expression, groupPos);
        }
    }
}

void LogicalProjection::computeFlatSchema() {
    copyChildSchema(0);
    auto childSchema = children[0]->getSchema();
    schema->clearExpressionsInScope();
    for (auto& expression : expressions) {
        if (childSchema->isExpressionInScope(*expression)) {
            schema->insertToScopeMayRepeat(expression, 0);
        } else {
            schema->insertToGroupAndScopeMayRepeat(expression, 0);
        }
    }
}

std::unordered_set<uint32_t> LogicalProjection::getDiscardedGroupsPos() const {
    auto groupsPosInScopeBeforeProjection = children[0]->getSchema()->getGroupsPosInScope();
    auto groupsPosInScopeAfterProjection = schema->getGroupsPosInScope();
    std::unordered_set<uint32_t> discardGroupsPos;
    for (auto i = 0u; i < schema->getNumGroups(); ++i) {
        if (groupsPosInScopeBeforeProjection.contains(i) &&
            !groupsPosInScopeAfterProjection.contains(i)) {
            discardGroupsPos.insert(i);
        }
    }
    return discardGroupsPos;
}

} // namespace planner
} // namespace lbug
