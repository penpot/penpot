#include "planner/operator/logical_aggregate.h"

#include "binder/expression/aggregate_function_expression.h"
#include "binder/expression/expression_util.h"
#include "planner/operator/factorization/flatten_resolver.h"
#include "planner/operator/schema.h"

namespace lbug {
namespace planner {

std::string LogicalAggregatePrintInfo::toString() const {
    std::string result = "";
    result += "Group By: ";
    result += binder::ExpressionUtil::toString(keys);
    result += ", Aggregates: ";
    result += binder::ExpressionUtil::toString(aggregates);
    return result;
}

void LogicalAggregate::computeFactorizedSchema() {
    createEmptySchema();
    auto groupPos = schema->createGroup();
    insertAllExpressionsToGroupAndScope(groupPos);
}

void LogicalAggregate::computeFlatSchema() {
    createEmptySchema();
    schema->createGroup();
    insertAllExpressionsToGroupAndScope(0 /* groupPos */);
}

f_group_pos_set LogicalAggregate::getGroupsPosToFlatten() {
    auto [unflatGroup, flattenedGroups] =
        FlattenAllButOne::getGroupsPosToFlatten(getAllKeys(), *children[0]->getSchema());
    // Flatten distinct aggregates if they are from a different group than the unflat key group
    // Regular aggregates can be processed when unflat, but distinct aggregates get added to their
    // own AggregateHashTable and have the same input limitations as the aggregate groups
    if (unflatGroup != INVALID_F_GROUP_POS) {
        for (const auto& aggregate : aggregates) {
            auto funcExpr = aggregate->constPtrCast<binder::AggregateFunctionExpression>();
            auto analyzer = GroupDependencyAnalyzer(false /* collectDependentExpr */,
                *children[0]->getSchema());
            analyzer.visit(aggregate);
            for (const auto& group : analyzer.getRequiredFlatGroups()) {
                flattenedGroups.insert(group);
            }
            if (funcExpr->isDistinct()) {
                for (const auto& group : analyzer.getDependentGroups()) {
                    if (group != unflatGroup) {
                        flattenedGroups.insert(group);
                    }
                }
            }
        }
    }
    return flattenedGroups;
}

std::string LogicalAggregate::getExpressionsForPrinting() const {
    std::string result = "Group By [";
    for (auto& expression : keys) {
        result += expression->toString() + ", ";
    }
    for (auto& expression : dependentKeys) {
        result += expression->toString() + ", ";
    }
    result += "], Aggregate [";
    for (auto& expression : aggregates) {
        result += expression->toString() + ", ";
    }
    result += "]";
    return result;
}

void LogicalAggregate::insertAllExpressionsToGroupAndScope(f_group_pos groupPos) {
    for (auto& expression : keys) {
        schema->insertToGroupAndScopeMayRepeat(expression, groupPos);
    }
    for (auto& expression : dependentKeys) {
        schema->insertToGroupAndScopeMayRepeat(expression, groupPos);
    }
    for (auto& expression : aggregates) {
        schema->insertToGroupAndScopeMayRepeat(expression, groupPos);
    }
}

} // namespace planner
} // namespace lbug
