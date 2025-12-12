#include "planner/operator/persistent/logical_merge.h"

#include "binder/expression/node_expression.h"
#include "common/cast.h"
#include "planner/operator/factorization/flatten_resolver.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

void LogicalMerge::computeFactorizedSchema() {
    copyChildSchema(0);
    for (auto& info : insertNodeInfos) {
        // Predicate iri is not matched but needs to be inserted.
        auto node = ku_dynamic_cast<NodeExpression*>(info.pattern.get());
        if (!schema->isExpressionInScope(*node->getInternalID())) {
            auto groupPos = schema->createGroup();
            schema->setGroupAsSingleState(groupPos);
            schema->insertToGroupAndScope(node->getInternalID(), groupPos);
        }
    }
}

void LogicalMerge::computeFlatSchema() {
    copyChildSchema(0);
    for (auto& info : insertNodeInfos) {
        auto node = ku_dynamic_cast<NodeExpression*>(info.pattern.get());
        schema->insertToGroupAndScopeMayRepeat(node->getInternalID(), 0);
    }
}

f_group_pos_set LogicalMerge::getGroupsPosToFlatten() {
    auto childSchema = children[0]->getSchema();
    return FlattenAll::getGroupsPosToFlatten(childSchema->getGroupsPosInScope(), *childSchema);
}

std::unique_ptr<LogicalOperator> LogicalMerge::copy() {
    auto merge = std::make_unique<LogicalMerge>(existenceMark, keys, children[0]->copy());
    merge->insertNodeInfos = copyVector(insertNodeInfos);
    merge->insertRelInfos = copyVector(insertRelInfos);
    merge->onCreateSetNodeInfos = copyVector(onCreateSetNodeInfos);
    merge->onCreateSetRelInfos = copyVector(onCreateSetRelInfos);
    merge->onMatchSetNodeInfos = copyVector(onMatchSetNodeInfos);
    merge->onMatchSetRelInfos = copyVector(onMatchSetRelInfos);
    return merge;
}

} // namespace planner
} // namespace lbug
