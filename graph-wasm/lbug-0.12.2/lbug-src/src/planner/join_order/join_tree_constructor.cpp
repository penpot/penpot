#include "planner/join_order/join_tree_constructor.h"

#include "binder/expression/expression_util.h"
#include "binder/query/reading_clause/bound_join_hint.h"
#include "common/exception/binder.h"
#include "common/exception/not_implemented.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

JoinTree JoinTreeConstructor::construct(std::shared_ptr<BoundJoinHintNode> root) {
    if (planningInfo.subqueryType == SubqueryPlanningType::CORRELATED) {
        throw NotImplementedException(
            stringFormat("Hint join pattern has correlation with previous "
                         "patterns. This is not supported yet."));
    }
    return JoinTree(constructTreeNode(root).treeNode);
}

static std::vector<std::shared_ptr<NodeExpression>> getJoinNodes(const SubqueryGraph& subgraph,
    const SubqueryGraph& otherSubgraph) {
    KU_ASSERT(&subgraph.queryGraph == &otherSubgraph.queryGraph);
    std::vector<std::shared_ptr<NodeExpression>> joinNodes;
    for (auto idx : subgraph.getNbrNodeIndices()) {
        if (otherSubgraph.queryNodesSelector[idx]) {
            joinNodes.push_back(subgraph.queryGraph.getQueryNode(idx));
        }
    }
    return joinNodes;
}

static std::vector<common::idx_t> intersect(std::vector<common::idx_t> left,
    std::vector<common::idx_t> right) {
    std::vector<common::idx_t> result;
    auto set = std::unordered_set<common::idx_t>{right.begin(), right.end()};
    for (auto idx : left) {
        if (set.contains(idx)) {
            result.push_back(idx);
        }
    }
    return result;
}

std::shared_ptr<NodeExpression> getIntersectNode(const QueryGraph& queryGraph,
    const std::vector<SubqueryGraph>& buildSubgraphs) {
    auto candidates = buildSubgraphs[0].getNbrNodeIndices();
    for (auto i = 1u; i < buildSubgraphs.size(); ++i) {
        candidates = intersect(candidates, buildSubgraphs[i].getNbrNodeIndices());
    }
    if (candidates.size() != 1) {
        throw BinderException("Cannot resolve join condition for multi-way join.");
    }
    return queryGraph.getQueryNode(candidates[0]);
}

JoinTreeConstructor::IntermediateResult JoinTreeConstructor::constructTreeNode(
    std::shared_ptr<BoundJoinHintNode> hintNode) {
    // Construct leaf scans.
    if (hintNode->isLeaf()) {
        if (ExpressionUtil::isNodePattern(*hintNode->nodeOrRel)) {
            return constructNodeScan(hintNode->nodeOrRel);
        } else {
            KU_ASSERT(ExpressionUtil::isRelPattern(*hintNode->nodeOrRel) ||
                      ExpressionUtil::isRecursiveRelPattern(*hintNode->nodeOrRel));
            return constructRelScan(hintNode->nodeOrRel);
        }
    }
    // Construct binary join.
    if (hintNode->isBinary()) {
        auto left = constructTreeNode(hintNode->children[0]);
        auto right = constructTreeNode(hintNode->children[1]);
        auto joinNodes = getJoinNodes(left.subqueryGraph, right.subqueryGraph);
        if (joinNodes.empty()) {
            joinNodes = getJoinNodes(right.subqueryGraph, left.subqueryGraph);
        }
        if (joinNodes.empty()) {
            throw BinderException(stringFormat("Cannot resolve join condition between {} and {}.",
                left.treeNode->toString(), right.treeNode->toString()));
        }
        auto newSubgraph = left.subqueryGraph;
        newSubgraph.addSubqueryGraph(right.subqueryGraph);
        auto predicates = Planner::getNewlyMatchedExprs(left.subqueryGraph, right.subqueryGraph,
            newSubgraph, queryGraphPredicates);
        // First try to construct as index nested loop join.
        auto nestedLoopTreeNode =
            tryConstructNestedLoopJoin(joinNodes, *left.treeNode, *right.treeNode, predicates);
        if (nestedLoopTreeNode != nullptr) {
            return {nestedLoopTreeNode, newSubgraph};
        }
        // Cannot construct index nested loop join. Fall back to hash join.
        auto extraInfo = std::make_unique<ExtraJoinTreeNodeInfo>(joinNodes);
        extraInfo->predicates = predicates;
        auto treeNode =
            std::make_shared<JoinTreeNode>(TreeNodeType::BINARY_JOIN, std::move(extraInfo));
        treeNode->addChild(left.treeNode);
        treeNode->addChild(right.treeNode);
        return {treeNode, newSubgraph};
    }
    // Construct multi-way join
    KU_ASSERT(hintNode->isMultiWay());
    auto probe = constructTreeNode(hintNode->children[0]);
    auto newSubgraph = probe.subqueryGraph;
    std::vector<std::shared_ptr<JoinTreeNode>> childrenNodes;
    childrenNodes.push_back(probe.treeNode);
    std::vector<SubqueryGraph> buildSubgraphs;
    for (auto i = 1u; i < hintNode->children.size(); ++i) {
        auto build = constructTreeNode(hintNode->children[i]);
        if (build.treeNode->type != TreeNodeType::REL_SCAN) {
            throw BinderException(stringFormat(
                "Cannot construct multi-way join because build side is not a relationship table."));
        }
        newSubgraph.addSubqueryGraph(build.subqueryGraph);
        childrenNodes.push_back(build.treeNode);
        buildSubgraphs.push_back(build.subqueryGraph);
    }
    auto joinNode = getIntersectNode(queryGraph, buildSubgraphs);
    auto subgraphs = buildSubgraphs;
    subgraphs.push_back(probe.subqueryGraph);
    auto predicates = Planner::getNewlyMatchedExprs(subgraphs, newSubgraph, queryGraphPredicates);
    auto extraInfo = std::make_unique<ExtraJoinTreeNodeInfo>(joinNode);
    extraInfo->predicates = predicates;
    auto treeNode =
        std::make_shared<JoinTreeNode>(TreeNodeType::MULTIWAY_JOIN, std::move(extraInfo));
    for (auto& child : childrenNodes) {
        treeNode->addChild(child);
    }
    return {treeNode, newSubgraph};
}

JoinTreeConstructor::IntermediateResult JoinTreeConstructor::constructNodeScan(
    std::shared_ptr<Expression> expr) {
    auto& node = expr->constCast<NodeExpression>();
    auto nodeIdx = queryGraph.getQueryNodeIdx(node.getUniqueName());
    auto emptySubgraph = SubqueryGraph(queryGraph);
    auto newSubgraph = SubqueryGraph(queryGraph);
    newSubgraph.addQueryNode(nodeIdx);
    auto extraInfo = std::make_unique<ExtraScanTreeNodeInfo>();
    // See Planner::planBaseTableScans for how we plan unnest correlated subqueries.
    if (planningInfo.subqueryType == SubqueryPlanningType::UNNEST_CORRELATED &&
        planningInfo.containsCorrExpr(*node.getInternalID())) {
        extraInfo->nodeInfo = std::make_unique<NodeRelScanInfo>(expr, expression_vector{});
        ;
        auto treeNode =
            std::make_shared<JoinTreeNode>(TreeNodeType::NODE_SCAN, std::move(extraInfo));
        return {treeNode, newSubgraph};
    }
    auto properties = propertyCollection.getProperties(*expr);
    auto predicates =
        Planner::getNewlyMatchedExprs(emptySubgraph, newSubgraph, queryGraphPredicates);
    auto nodeScanInfo = std::make_unique<NodeRelScanInfo>(expr, properties);
    nodeScanInfo->predicates = predicates;
    extraInfo->nodeInfo = std::move(nodeScanInfo);
    auto treeNode = std::make_shared<JoinTreeNode>(TreeNodeType::NODE_SCAN, std::move(extraInfo));
    return {treeNode, newSubgraph};
}

JoinTreeConstructor::IntermediateResult JoinTreeConstructor::constructRelScan(
    std::shared_ptr<binder::Expression> expr) {
    auto& rel = expr->constCast<RelExpression>();
    auto relIdx = queryGraph.getQueryRelIdx(rel.getUniqueName());
    auto emptySubgraph = SubqueryGraph(queryGraph);
    auto newSubgraph = SubqueryGraph(queryGraph);
    newSubgraph.addQueryRel(relIdx);
    auto properties = propertyCollection.getProperties(*expr);
    auto predicates =
        Planner::getNewlyMatchedExprs(emptySubgraph, newSubgraph, queryGraphPredicates);
    auto relScanInfo = NodeRelScanInfo(expr, properties);
    relScanInfo.predicates = predicates;
    auto extraInfo = std::make_unique<ExtraScanTreeNodeInfo>();
    extraInfo->relInfos.push_back(std::move(relScanInfo));
    auto treeNode = std::make_shared<JoinTreeNode>(TreeNodeType::REL_SCAN, std::move(extraInfo));
    return {treeNode, newSubgraph};
}

std::shared_ptr<JoinTreeNode> JoinTreeConstructor::tryConstructNestedLoopJoin(
    std::vector<std::shared_ptr<NodeExpression>> joinNodes, const JoinTreeNode& leftRoot,
    const JoinTreeNode& rightRoot, const binder::expression_vector& predicates) {
    if (joinNodes.size() > 1) {
        return nullptr;
    }
    if (leftRoot.type == TreeNodeType::REL_SCAN && rightRoot.type == TreeNodeType::NODE_SCAN) {
        return tryConstructNestedLoopJoin(joinNodes, rightRoot, leftRoot, predicates);
    }
    if (leftRoot.type != TreeNodeType::NODE_SCAN) {
        return nullptr;
    }
    if (rightRoot.type != TreeNodeType::REL_SCAN) {
        return nullptr;
    }
    auto joinNode = joinNodes[0];
    auto& leftExtraInfo = leftRoot.extraInfo->constCast<ExtraScanTreeNodeInfo>();
    auto& rightExtraInfo = rightRoot.extraInfo->constCast<ExtraScanTreeNodeInfo>();
    if (*leftExtraInfo.nodeInfo->nodeOrRel != *joinNode) {
        return nullptr;
    }
    auto newExtraInfo = std::make_unique<ExtraScanTreeNodeInfo>(leftExtraInfo);
    newExtraInfo->relInfos.push_back(rightExtraInfo.relInfos[0]);
    newExtraInfo->predicates = predicates;
    return std::make_shared<JoinTreeNode>(TreeNodeType::NODE_SCAN, std::move(newExtraInfo));
}

} // namespace planner
} // namespace lbug
