#include "optimizer/agg_key_dependency_optimizer.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/property_expression.h"
#include "planner/operator/logical_aggregate.h"
#include "planner/operator/logical_distinct.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace optimizer {

void AggKeyDependencyOptimizer::rewrite(planner::LogicalPlan* plan) {
    visitOperator(plan->getLastOperator().get());
}

void AggKeyDependencyOptimizer::visitOperator(planner::LogicalOperator* op) {
    // bottom up traversal
    for (auto i = 0u; i < op->getNumChildren(); ++i) {
        visitOperator(op->getChild(i).get());
    }
    visitOperatorSwitch(op);
}

void AggKeyDependencyOptimizer::visitAggregate(planner::LogicalOperator* op) {
    auto agg = (LogicalAggregate*)op;
    auto [keys, dependentKeys] = resolveKeysAndDependentKeys(agg->getKeys());
    agg->setKeys(keys);
    agg->setDependentKeys(dependentKeys);
}

void AggKeyDependencyOptimizer::visitDistinct(planner::LogicalOperator* op) {
    auto distinct = (LogicalDistinct*)op;
    auto [keys, dependentKeys] = resolveKeysAndDependentKeys(distinct->getKeys());
    distinct->setKeys(keys);
    distinct->setPayloads(dependentKeys);
}

std::pair<binder::expression_vector, binder::expression_vector>
AggKeyDependencyOptimizer::resolveKeysAndDependentKeys(const expression_vector& inputKeys) {
    // Consider example RETURN a.ID, a.age, COUNT(*).
    // We first collect a.ID into primaryKeys. Then collect "a" into primaryVarNames.
    // Finally, we loop through all group by keys to put non-primary key properties under name "a"
    // into dependentKeyExpressions.

    // Collect primary variables from keys.
    std::unordered_set<std::string> primaryVarNames;
    for (auto& key : inputKeys) {
        if (key->expressionType == ExpressionType::PROPERTY) {
            auto property = (PropertyExpression*)key.get();
            if (property->isPrimaryKey() || property->isInternalID()) {
                primaryVarNames.insert(property->getVariableName());
            }
        }
    }
    // Resolve key dependency.
    binder::expression_vector keys;
    binder::expression_vector dependentKeys;
    for (auto& key : inputKeys) {
        if (key->expressionType == ExpressionType::PROPERTY) {
            auto property = (PropertyExpression*)key.get();
            if (property->isPrimaryKey() ||
                property->isInternalID()) { // NOLINT(bugprone-branch-clone): Collapsing
                                            // is a logical error.
                // Primary properties are always keys.
                keys.push_back(key);
            } else if (primaryVarNames.contains(property->getVariableName())) {
                // Properties depend on any primary property are dependent keys.
                // e.g. a.age depends on a._id
                dependentKeys.push_back(key);
            } else {
                keys.push_back(key);
            }
        } else if (ExpressionUtil::isNodePattern(*key) || ExpressionUtil::isRelPattern(*key)) {
            if (primaryVarNames.contains(key->getUniqueName())) {
                // e.g. a depends on a._id
                dependentKeys.push_back(key);
            } else {
                keys.push_back(key);
            }
        } else {
            keys.push_back(key);
        }
    }
    return std::make_pair(std::move(keys), std::move(dependentKeys));
}

} // namespace optimizer
} // namespace lbug
