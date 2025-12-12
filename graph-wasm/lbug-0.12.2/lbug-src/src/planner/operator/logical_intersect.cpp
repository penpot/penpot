#include "planner/operator/logical_intersect.h"

namespace lbug {
namespace planner {

f_group_pos_set LogicalIntersect::getGroupsPosToFlattenOnProbeSide() {
    f_group_pos_set result;
    for (auto& keyNodeID : keyNodeIDs) {
        result.insert(children[0]->getSchema()->getGroupPos(*keyNodeID));
    }
    return result;
}

f_group_pos_set LogicalIntersect::getGroupsPosToFlattenOnBuildSide(uint32_t buildIdx) {
    f_group_pos_set result;
    auto childIdx = buildIdx + 1; // skip probe
    result.insert(children[childIdx]->getSchema()->getGroupPos(*keyNodeIDs[buildIdx]));
    return result;
}

void LogicalIntersect::computeFactorizedSchema() {
    auto probeSchema = children[0]->getSchema();
    schema = probeSchema->copy();
    // Write intersect node and rels into a new group regardless of whether rel is n-n.
    auto outGroupPos = schema->createGroup();
    schema->insertToGroupAndScope(intersectNodeID, outGroupPos);
    for (auto i = 1u; i < children.size(); ++i) {
        auto buildSchema = children[i]->getSchema();
        auto keyNodeID = keyNodeIDs[i - 1];
        // Write rel properties into output group.
        for (auto& expression : buildSchema->getExpressionsInScope()) {
            if (expression->getUniqueName() == intersectNodeID->getUniqueName() ||
                expression->getUniqueName() == keyNodeID->getUniqueName()) {
                continue;
            }
            schema->insertToGroupAndScope(expression, outGroupPos);
        }
    }
}

void LogicalIntersect::computeFlatSchema() {
    auto probeSchema = children[0]->getSchema();
    schema = probeSchema->copy();
    schema->insertToGroupAndScope(intersectNodeID, 0);
    for (auto i = 1u; i < children.size(); ++i) {
        auto buildSchema = children[i]->getSchema();
        auto keyNodeID = keyNodeIDs[i - 1];
        for (auto& expression : buildSchema->getExpressionsInScope()) {
            if (expression->getUniqueName() == intersectNodeID->getUniqueName() ||
                expression->getUniqueName() == keyNodeID->getUniqueName()) {
                continue;
            }
            schema->insertToGroupAndScope(expression, 0);
        }
    }
}

std::unique_ptr<LogicalOperator> LogicalIntersect::copy() {
    std::vector<std::shared_ptr<LogicalOperator>> buildChildren;
    for (auto i = 1u; i < children.size(); ++i) {
        buildChildren.push_back(children[i]->copy());
    }
    auto op = make_unique<LogicalIntersect>(intersectNodeID, keyNodeIDs, children[0]->copy(),
        std::move(buildChildren), cardinality);
    op->sipInfo = sipInfo;
    op->cardinality = cardinality;
    return op;
}

} // namespace planner
} // namespace lbug
