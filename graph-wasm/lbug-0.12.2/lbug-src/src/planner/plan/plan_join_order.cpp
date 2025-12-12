#include <cmath>

#include "binder/expression_visitor.h"
#include "common/enums/join_type.h"
#include "common/enums/rel_direction.h"
#include "common/utils.h"
#include "planner/join_order/cost_model.h"
#include "planner/join_order/join_plan_solver.h"
#include "planner/join_order/join_tree_constructor.h"
#include "planner/operator/scan/logical_scan_node_table.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

LogicalPlan Planner::planQueryGraphCollectionInNewContext(
    const QueryGraphCollection& queryGraphCollection, const QueryGraphPlanningInfo& info) {
    auto prevContext = enterNewContext();
    auto plan = planQueryGraphCollection(queryGraphCollection, info);
    exitContext(std::move(prevContext));
    return plan;
}

static int32_t getConnectedQueryGraphIdx(const QueryGraphCollection& queryGraphCollection,
    const QueryGraphPlanningInfo& info) {
    for (auto i = 0u; i < queryGraphCollection.getNumQueryGraphs(); ++i) {
        auto queryGraph = queryGraphCollection.getQueryGraph(i);
        for (auto& queryNode : queryGraph->getQueryNodes()) {
            if (info.containsCorrExpr(*queryNode->getInternalID())) {
                return i;
            }
        }
    }
    return -1;
}

LogicalPlan Planner::planQueryGraphCollection(const QueryGraphCollection& queryGraphCollection,
    const QueryGraphPlanningInfo& info) {
    KU_ASSERT(queryGraphCollection.getNumQueryGraphs() > 0);
    auto& corrExprs = info.corrExprs;
    int32_t queryGraphIdxToPlanExpressionsScan = -1;
    if (info.subqueryType == SubqueryPlanningType::CORRELATED) {
        // Pick a query graph to plan ExpressionsScan. If -1 is returned, we fall back to cross
        // product.
        queryGraphIdxToPlanExpressionsScan = getConnectedQueryGraphIdx(queryGraphCollection, info);
    }
    std::unordered_set<uint32_t> evaluatedPredicatesIndices;
    std::vector<LogicalPlan> planPerQueryGraph;
    for (auto i = 0u; i < queryGraphCollection.getNumQueryGraphs(); ++i) {
        auto queryGraph = queryGraphCollection.getQueryGraph(i);
        // Extract predicates for current query graph
        std::unordered_set<uint32_t> predicateToEvaluateIndices;
        for (auto j = 0u; j < info.predicates.size(); ++j) {
            if (info.predicates[j]->expressionType == ExpressionType::LITERAL) {
                continue;
            }
            if (evaluatedPredicatesIndices.contains(j)) {
                continue;
            }
            if (queryGraph->canProjectExpression(info.predicates[j])) {
                predicateToEvaluateIndices.insert(j);
            }
        }
        evaluatedPredicatesIndices.insert(predicateToEvaluateIndices.begin(),
            predicateToEvaluateIndices.end());
        expression_vector predicatesToEvaluate;
        for (auto idx : predicateToEvaluateIndices) {
            predicatesToEvaluate.push_back(info.predicates[idx]);
        }
        LogicalPlan plan;
        auto newInfo = info;
        newInfo.predicates = predicatesToEvaluate;
        switch (info.subqueryType) {
        case SubqueryPlanningType::NONE:
        case SubqueryPlanningType::UNNEST_CORRELATED: {
            plan = planQueryGraph(*queryGraph, newInfo);
        } break;
        case SubqueryPlanningType::CORRELATED: {
            if (i == (uint32_t)queryGraphIdxToPlanExpressionsScan) {
                // Plan ExpressionsScan with current query graph.
                plan = planQueryGraph(*queryGraph, newInfo);
            } else {
                // Plan current query graph as an isolated query graph.
                newInfo.subqueryType = SubqueryPlanningType::NONE;
                plan = planQueryGraph(*queryGraph, newInfo);
            }
        } break;
        default:
            KU_UNREACHABLE;
        }
        planPerQueryGraph.push_back(std::move(plan));
    }
    // Fail to plan ExpressionsScan with any query graph. Plan it independently and fall back to
    // cross product.
    if (info.subqueryType == SubqueryPlanningType::CORRELATED &&
        queryGraphIdxToPlanExpressionsScan == -1) {
        auto plan = LogicalPlan();
        appendExpressionsScan(corrExprs, plan);
        appendDistinct(corrExprs, plan);
        planPerQueryGraph.push_back(std::move(plan));
    }
    // Take cross products
    auto plan = planPerQueryGraph[0].copy();
    for (auto i = 1u; i < planPerQueryGraph.size(); ++i) {
        appendCrossProduct(plan, planPerQueryGraph[i], plan);
    }
    // Apply remaining predicates
    expression_vector remainingPredicates;
    for (auto i = 0u; i < info.predicates.size(); ++i) {
        if (!evaluatedPredicatesIndices.contains(i)) {
            remainingPredicates.push_back(info.predicates[i]);
        }
    }
    for (auto& predicate : remainingPredicates) {
        appendFilter(predicate, plan);
    }
    return plan;
}

LogicalPlan Planner::planQueryGraph(const QueryGraph& queryGraph,
    const QueryGraphPlanningInfo& info) {
    context.init(&queryGraph, info.predicates);
    cardinalityEstimator.init(queryGraph);
    if (info.hint != nullptr) {
        auto constructor =
            JoinTreeConstructor(queryGraph, propertyExprCollection, info.predicates, info);
        auto joinTree = constructor.construct(info.hint);
        auto plan = JoinPlanSolver(this).solve(joinTree);
        return plan.copy();
    }
    planBaseTableScans(info);
    context.currentLevel++;
    while (context.currentLevel < context.maxLevel) {
        planLevel(context.currentLevel++);
    }

    auto& plans = context.getPlans(context.getFullyMatchedSubqueryGraph());
    auto bestIdx = 0;
    for (auto i = 1u; i < plans.size(); ++i) {
        if (plans[i].getCost() < plans[bestIdx].getCost()) {
            bestIdx = i;
        }
    }
    auto bestPlan = plans[bestIdx].copy();
    if (queryGraph.isEmpty()) {
        appendEmptyResult(bestPlan);
    }
    return bestPlan;
}

void Planner::planLevel(uint32_t level) {
    KU_ASSERT(level > 1);
    if (level > MAX_LEVEL_TO_PLAN_EXACTLY) {
        planLevelApproximately(level);
    } else {
        planLevelExactly(level);
    }
}

void Planner::planLevelExactly(uint32_t level) {
    auto maxLeftLevel = floor(level / 2.0);
    for (auto leftLevel = 1u; leftLevel <= maxLeftLevel; ++leftLevel) {
        auto rightLevel = level - leftLevel;
        if (leftLevel > 1) { // wcoj requires at least 2 rels
            planWCOJoin(leftLevel, rightLevel);
        }
        planInnerJoin(leftLevel, rightLevel);
    }
}

void Planner::planLevelApproximately(uint32_t level) {
    planInnerJoin(1, level - 1);
}

void Planner::planBaseTableScans(const QueryGraphPlanningInfo& info) {
    auto queryGraph = context.getQueryGraph();
    switch (info.subqueryType) {
    case SubqueryPlanningType::NONE: {
        for (auto nodePos = 0u; nodePos < queryGraph->getNumQueryNodes(); ++nodePos) {
            planNodeScan(nodePos);
        }
    } break;
    case SubqueryPlanningType::UNNEST_CORRELATED: {
        for (auto nodePos = 0u; nodePos < queryGraph->getNumQueryNodes(); ++nodePos) {
            auto queryNode = queryGraph->getQueryNode(nodePos);
            if (info.containsCorrExpr(*queryNode->getInternalID())) {
                // NodeID will be a join condition with outer plan so very likely we will apply a
                // semi mask later in the optimization stage. So we can assume the cardinality will
                // not exceed outer plan cardinality.
                cardinalityEstimator.rectifyCardinality(*queryNode->getInternalID(),
                    info.corrExprsCard);
                // In un-nested subquery, e.g. MATCH (a) OPTIONAL MATCH (a)-[e1]->(b), the inner
                // query ("(a)-[e1]->(b)") needs to scan a, which is already scanned in the outer
                // query (a). To avoid scanning storage twice, we keep track of node table "a" and
                // make sure when planning inner query, we only scan internal ID of "a".
                planNodeIDScan(nodePos);
            } else {
                planNodeScan(nodePos);
            }
        }
    } break;
    case SubqueryPlanningType::CORRELATED: {
        for (auto nodePos = 0u; nodePos < queryGraph->getNumQueryNodes(); ++nodePos) {
            auto queryNode = queryGraph->getQueryNode(nodePos);
            if (info.containsCorrExpr(*queryNode->getInternalID())) {
                continue;
            }
            planNodeScan(nodePos);
        }
        planCorrelatedExpressionsScan(info);
    } break;
    default:
        KU_UNREACHABLE;
    }
    for (auto relPos = 0u; relPos < queryGraph->getNumQueryRels(); ++relPos) {
        planRelScan(relPos);
    }
}

void Planner::planCorrelatedExpressionsScan(const QueryGraphPlanningInfo& info) {
    auto queryGraph = context.getQueryGraph();
    auto newSubgraph = context.getEmptySubqueryGraph();
    auto& corrExprs = info.corrExprs;
    for (auto nodePos = 0u; nodePos < queryGraph->getNumQueryNodes(); ++nodePos) {
        auto queryNode = queryGraph->getQueryNode(nodePos);
        if (info.containsCorrExpr(*queryNode->getInternalID())) {
            newSubgraph.addQueryNode(nodePos);
        }
    }
    auto plan = LogicalPlan();
    appendExpressionsScan(corrExprs, plan);
    plan.getLastOperator()->setCardinality(info.corrExprsCard);
    auto predicates = getNewlyMatchedExprs(context.getEmptySubqueryGraph(), newSubgraph,
        context.getWhereExpressions());
    appendFilters(predicates, plan);
    appendDistinct(corrExprs, plan);
    context.addPlan(newSubgraph, std::move(plan));
}

void Planner::planNodeScan(uint32_t nodePos) {
    auto node = context.queryGraph->getQueryNode(nodePos);
    auto newSubgraph = context.getEmptySubqueryGraph();
    newSubgraph.addQueryNode(nodePos);
    auto plan = LogicalPlan();
    auto properties = getProperties(*node);
    appendScanNodeTable(node->getInternalID(), node->getTableIDs(), properties, plan);
    auto predicates = getNewlyMatchedExprs(context.getEmptySubqueryGraph(), newSubgraph,
        context.getWhereExpressions());
    appendFilters(predicates, plan);
    context.addPlan(newSubgraph, std::move(plan));
}

void Planner::planNodeIDScan(uint32_t nodePos) {
    auto node = context.queryGraph->getQueryNode(nodePos);
    auto newSubgraph = context.getEmptySubqueryGraph();
    newSubgraph.addQueryNode(nodePos);
    auto plan = LogicalPlan();
    appendScanNodeTable(node->getInternalID(), node->getTableIDs(), {}, plan);
    context.addPlan(newSubgraph, std::move(plan));
}

static std::pair<std::shared_ptr<NodeExpression>, std::shared_ptr<NodeExpression>>
getBoundAndNbrNodes(const RelExpression& rel, ExtendDirection direction) {
    KU_ASSERT(direction != ExtendDirection::BOTH);
    auto boundNode = direction == ExtendDirection::FWD ? rel.getSrcNode() : rel.getDstNode();
    auto dstNode = direction == ExtendDirection::FWD ? rel.getDstNode() : rel.getSrcNode();
    return make_pair(boundNode, dstNode);
}

static ExtendDirection getExtendDirection(const binder::RelExpression& relExpression,
    const binder::NodeExpression& boundNode) {
    if (relExpression.getDirectionType() == binder::RelDirectionType::BOTH) {
        KU_ASSERT(relExpression.getExtendDirections().size() == common::NUM_REL_DIRECTIONS);
        return ExtendDirection::BOTH;
    }
    if (relExpression.getSrcNodeName() == boundNode.getUniqueName()) {
        return ExtendDirection::FWD;
    } else {
        return ExtendDirection::BWD;
    }
}

void Planner::planRelScan(uint32_t relPos) {
    const auto rel = context.queryGraph->getQueryRel(relPos);
    auto newSubgraph = context.getEmptySubqueryGraph();
    newSubgraph.addQueryRel(relPos);
    const auto predicates = getNewlyMatchedExprs(context.getEmptySubqueryGraph(), newSubgraph,
        context.getWhereExpressions());
    for (const auto direction : rel->getExtendDirections()) {
        auto plan = LogicalPlan();
        auto [boundNode, nbrNode] = getBoundAndNbrNodes(*rel, direction);
        const auto extendDirection = getExtendDirection(*rel, *boundNode);
        appendScanNodeTable(boundNode->getInternalID(), boundNode->getTableIDs(), {}, plan);
        appendExtend(boundNode, nbrNode, rel, extendDirection, getProperties(*rel), plan);
        appendFilters(predicates, plan);
        context.addPlan(newSubgraph, std::move(plan));
    }
}

void Planner::appendExtend(std::shared_ptr<NodeExpression> boundNode,
    std::shared_ptr<NodeExpression> nbrNode, std::shared_ptr<RelExpression> rel,
    ExtendDirection direction, const binder::expression_vector& properties, LogicalPlan& plan) {
    switch (rel->getRelType()) {
    case QueryRelType::NON_RECURSIVE: {
        auto extendFromSource = *boundNode == *rel->getSrcNode();
        appendNonRecursiveExtend(boundNode, nbrNode, rel, direction, extendFromSource, properties,
            plan);
    } break;
    case QueryRelType::VARIABLE_LENGTH_WALK:
    case QueryRelType::VARIABLE_LENGTH_TRAIL:
    case QueryRelType::VARIABLE_LENGTH_ACYCLIC:
    case QueryRelType::SHORTEST:
    case QueryRelType::ALL_SHORTEST:
    case QueryRelType::WEIGHTED_SHORTEST:
    case QueryRelType::ALL_WEIGHTED_SHORTEST: {
        appendRecursiveExtend(boundNode, nbrNode, rel, direction, plan);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

static std::unordered_map<uint32_t, std::vector<std::shared_ptr<RelExpression>>>
populateIntersectRelCandidates(const QueryGraph& queryGraph, const SubqueryGraph& subgraph) {
    std::unordered_map<uint32_t, std::vector<std::shared_ptr<RelExpression>>>
        intersectNodePosToRelsMap;
    for (auto relPos : subgraph.getRelNbrPositions()) {
        auto rel = queryGraph.getQueryRel(relPos);
        if (!queryGraph.containsQueryNode(rel->getSrcNodeName()) ||
            !queryGraph.containsQueryNode(rel->getDstNodeName())) {
            continue;
        }
        auto srcNodePos = queryGraph.getQueryNodeIdx(rel->getSrcNodeName());
        auto dstNodePos = queryGraph.getQueryNodeIdx(rel->getDstNodeName());
        auto isSrcConnected = subgraph.queryNodesSelector[srcNodePos];
        auto isDstConnected = subgraph.queryNodesSelector[dstNodePos];
        // Closing rel should be handled with inner join.
        if (isSrcConnected && isDstConnected) {
            continue;
        }
        auto intersectNodePos = isSrcConnected ? dstNodePos : srcNodePos;
        if (!intersectNodePosToRelsMap.contains(intersectNodePos)) {
            intersectNodePosToRelsMap.insert(
                {intersectNodePos, std::vector<std::shared_ptr<RelExpression>>{}});
        }
        intersectNodePosToRelsMap.at(intersectNodePos).push_back(rel);
    }
    return intersectNodePosToRelsMap;
}

void Planner::planWCOJoin(uint32_t leftLevel, uint32_t rightLevel) {
    KU_ASSERT(leftLevel <= rightLevel);
    auto queryGraph = context.getQueryGraph();
    for (auto& rightSubgraph : context.subPlansTable->getSubqueryGraphs(rightLevel)) {
        auto candidates = populateIntersectRelCandidates(*queryGraph, rightSubgraph);
        for (auto& [intersectNodePos, rels] : candidates) {
            if (rels.size() == leftLevel) {
                auto intersectNode = queryGraph->getQueryNode(intersectNodePos);
                planWCOJoin(rightSubgraph, rels, intersectNode);
            }
        }
    }
}

static LogicalOperator* getSequentialScan(LogicalOperator* op) {
    switch (op->getOperatorType()) {
    case LogicalOperatorType::FLATTEN:
    case LogicalOperatorType::FILTER:
    case LogicalOperatorType::EXTEND:
    case LogicalOperatorType::PROJECTION: { // operators we directly search through
        return getSequentialScan(op->getChild(0).get());
    }
    case LogicalOperatorType::SCAN_NODE_TABLE: {
        return op;
    }
    default:
        return nullptr;
    }
}

// Check whether given node ID has sequential guarantee on the plan.
static bool isNodeSequentialOnPlan(const LogicalPlan& plan, const NodeExpression& node) {
    const auto seqScan = getSequentialScan(plan.getLastOperator().get());
    if (seqScan == nullptr) {
        return false;
    }
    const auto sequentialScan = ku_dynamic_cast<LogicalScanNodeTable*>(seqScan);
    return sequentialScan->getNodeID()->getUniqueName() == node.getInternalID()->getUniqueName();
}

// As a heuristic for wcoj, we always pick rel scan that starts from the bound node.
static LogicalPlan getWCOJBuildPlanForRel(const std::vector<LogicalPlan>& candidatePlans,
    const NodeExpression& boundNode) {
    for (auto& candidatePlan : candidatePlans) {
        if (isNodeSequentialOnPlan(candidatePlan, boundNode)) {
            return candidatePlan.copy();
        }
    }
    return LogicalPlan();
}

void Planner::planWCOJoin(const SubqueryGraph& subgraph,
    const std::vector<std::shared_ptr<RelExpression>>& rels,
    const std::shared_ptr<NodeExpression>& intersectNode) {
    auto newSubgraph = subgraph;
    std::vector<SubqueryGraph> prevSubgraphs;
    prevSubgraphs.push_back(subgraph);
    expression_vector boundNodeIDs;
    std::vector<LogicalPlan> relPlans;
    for (auto& rel : rels) {
        auto boundNode = rel->getSrcNodeName() == intersectNode->getUniqueName() ?
                             rel->getDstNode() :
                             rel->getSrcNode();

        // stop if the rel pattern's supported rel directions don't contain the current direction
        const auto extendDirection = getExtendDirection(*rel, *boundNode);
        if (extendDirection != ExtendDirection::BOTH &&
            !containsValue(rel->getExtendDirections(), extendDirection)) {
            return;
        }

        boundNodeIDs.push_back(boundNode->getInternalID());
        auto relPos = context.getQueryGraph()->getQueryRelIdx(rel->getUniqueName());
        auto prevSubgraph = context.getEmptySubqueryGraph();
        prevSubgraph.addQueryRel(relPos);
        prevSubgraphs.push_back(subgraph);
        newSubgraph.addQueryRel(relPos);
        // fetch build plans for rel
        auto relSubgraph = context.getEmptySubqueryGraph();
        relSubgraph.addQueryRel(relPos);
        KU_ASSERT(context.subPlansTable->containSubgraphPlans(relSubgraph));
        auto& relPlanCandidates = context.subPlansTable->getSubgraphPlans(relSubgraph);
        auto relPlan = getWCOJBuildPlanForRel(relPlanCandidates, *boundNode);
        if (relPlan.isEmpty()) { // Cannot find a suitable rel plan.
            return;
        }
        relPlans.push_back(std::move(relPlan));
    }
    auto predicates =
        getNewlyMatchedExprs(prevSubgraphs, newSubgraph, context.getWhereExpressions());
    for (auto& leftPlan : context.getPlans(subgraph)) {
        // Disable WCOJ if intersect node is in the scope of probe plan. This happens in the case
        // like, MATCH (a)-[e1]->(b), (b)-[e2]->(a), (a)-[e3]->(b).
        // When we perform edge-at-a-time enumeration, at some point we will in the state of e1 as
        // probe side and e2, e3 as build side and we attempt to apply WCOJ. However, the right
        // approach is to build e1, e2, e3 and intersect on a common node (either a or b).
        // I tend to disable WCOJ for this case for now. The proper fix should be move to
        // node-at-a-time enumeration and re-enable WCOJ.
        // TODO(Xiyang): Fixme according to the description above.
        if (leftPlan.getSchema()->isExpressionInScope(*intersectNode->getInternalID())) {
            continue;
        }
        auto leftPlanCopy = leftPlan.copy();
        std::vector<LogicalPlan> rightPlansCopy;
        rightPlansCopy.reserve(relPlans.size());
        for (auto& relPlan : relPlans) {
            rightPlansCopy.push_back(relPlan.copy());
        }
        appendIntersect(intersectNode->getInternalID(), boundNodeIDs, leftPlanCopy, rightPlansCopy);
        for (auto& predicate : predicates) {
            appendFilter(predicate, leftPlanCopy);
        }
        context.subPlansTable->addPlan(newSubgraph, std::move(leftPlanCopy));
    }
}

// E.g. Query graph (a)-[e1]->(b), (b)-[e2]->(a) and join between (a)-[e1] and [e2]
// Since (b) is not in the scope of any join subgraph, join node is analyzed as (a) only, However,
// [e1] and [e2] are also connected at (b) implicitly. So actual join nodes should be (a) and (b).
// We prune such join.
// Note that this does not mean we may lose good plan. An equivalent join can be found between [e2]
// and (a)-[e1]->(b).
static bool needPruneImplicitJoins(const SubqueryGraph& leftSubgraph,
    const SubqueryGraph& rightSubgraph, uint32_t numJoinNodes) {
    auto leftNodePositions = leftSubgraph.getNodePositionsIgnoringNodeSelector();
    auto rightNodePositions = rightSubgraph.getNodePositionsIgnoringNodeSelector();
    auto intersectionSize = 0u;
    for (auto& pos : leftNodePositions) {
        if (rightNodePositions.contains(pos)) {
            intersectionSize++;
        }
    }
    return intersectionSize != numJoinNodes;
}

void Planner::planInnerJoin(uint32_t leftLevel, uint32_t rightLevel) {
    KU_ASSERT(leftLevel <= rightLevel);
    for (auto& rightSubgraph : context.subPlansTable->getSubqueryGraphs(rightLevel)) {
        for (auto& nbrSubgraph : rightSubgraph.getNbrSubgraphs(leftLevel)) {
            // E.g. MATCH (a)->(b) MATCH (b)->(c)
            // Since we merge query graph for multipart query, during enumeration for the second
            // match, the query graph is (a)->(b)->(c). However, we omit plans corresponding to the
            // first match (i.e. (a)->(b)).
            if (!context.containPlans(nbrSubgraph)) {
                continue;
            }
            auto joinNodePositions = rightSubgraph.getConnectedNodePos(nbrSubgraph);
            auto joinNodes = context.queryGraph->getQueryNodes(joinNodePositions);
            if (needPruneImplicitJoins(nbrSubgraph, rightSubgraph, joinNodes.size())) {
                continue;
            }
            // If index nested loop (INL) join is possible, we prune hash join plans
            if (tryPlanINLJoin(rightSubgraph, nbrSubgraph, joinNodes)) {
                continue;
            }
            planInnerHashJoin(rightSubgraph, nbrSubgraph, joinNodes, leftLevel != rightLevel);
        }
    }
}

bool Planner::tryPlanINLJoin(const SubqueryGraph& subgraph, const SubqueryGraph& otherSubgraph,
    const std::vector<std::shared_ptr<NodeExpression>>& joinNodes) {
    if (joinNodes.size() > 1) {
        return false;
    }
    if (!subgraph.isSingleRel() && !otherSubgraph.isSingleRel()) {
        return false;
    }
    if (subgraph.isSingleRel()) { // Always put single rel subgraph to right.
        return tryPlanINLJoin(otherSubgraph, subgraph, joinNodes);
    }
    auto relPos = UINT32_MAX;
    for (auto i = 0u; i < context.queryGraph->getNumQueryRels(); ++i) {
        if (otherSubgraph.queryRelsSelector[i]) {
            relPos = i;
        }
    }
    KU_ASSERT(relPos != UINT32_MAX);
    auto rel = context.queryGraph->getQueryRel(relPos);
    const auto& boundNode = joinNodes[0];
    auto nbrNode =
        boundNode->getUniqueName() == rel->getSrcNodeName() ? rel->getDstNode() : rel->getSrcNode();
    auto extendDirection = getExtendDirection(*rel, *boundNode);
    if (extendDirection != common::ExtendDirection::BOTH &&
        !common::containsValue(rel->getExtendDirections(), extendDirection)) {
        return false;
    }
    auto newSubgraph = subgraph;
    newSubgraph.addQueryRel(relPos);
    auto predicates = getNewlyMatchedExprs(subgraph, newSubgraph, context.getWhereExpressions());
    bool hasAppliedINLJoin = false;
    for (auto& prevPlan : context.getPlans(subgraph)) {
        if (isNodeSequentialOnPlan(prevPlan, *boundNode)) {
            auto plan = prevPlan.copy();
            appendExtend(boundNode, nbrNode, rel, extendDirection, getProperties(*rel), plan);
            appendFilters(predicates, plan);
            context.addPlan(newSubgraph, std::move(plan));
            hasAppliedINLJoin = true;
        }
    }
    return hasAppliedINLJoin;
}

void Planner::planInnerHashJoin(const SubqueryGraph& subgraph, const SubqueryGraph& otherSubgraph,
    const std::vector<std::shared_ptr<NodeExpression>>& joinNodes, bool flipPlan) {
    auto newSubgraph = subgraph;
    newSubgraph.addSubqueryGraph(otherSubgraph);
    auto maxCost = context.subPlansTable->getMaxCost(newSubgraph);
    expression_vector joinNodeIDs;
    for (auto& joinNode : joinNodes) {
        joinNodeIDs.push_back(joinNode->getInternalID());
    }
    auto predicates =
        getNewlyMatchedExprs(subgraph, otherSubgraph, newSubgraph, context.getWhereExpressions());
    for (auto& leftPlan : context.getPlans(subgraph)) {
        for (auto& rightPlan : context.getPlans(otherSubgraph)) {
            if (CostModel::computeHashJoinCost(joinNodeIDs, leftPlan, rightPlan) < maxCost) {
                auto leftPlanProbeCopy = leftPlan.copy();
                auto rightPlanBuildCopy = rightPlan.copy();
                appendHashJoin(joinNodeIDs, JoinType::INNER, leftPlanProbeCopy, rightPlanBuildCopy,
                    leftPlanProbeCopy);
                appendFilters(predicates, leftPlanProbeCopy);
                context.addPlan(newSubgraph, std::move(leftPlanProbeCopy));
            }
            // flip build and probe side to get another HashJoin plan
            if (flipPlan &&
                CostModel::computeHashJoinCost(joinNodeIDs, rightPlan, leftPlan) < maxCost) {
                auto leftPlanBuildCopy = leftPlan.copy();
                auto rightPlanProbeCopy = rightPlan.copy();
                appendHashJoin(joinNodeIDs, JoinType::INNER, rightPlanProbeCopy, leftPlanBuildCopy,
                    rightPlanProbeCopy);
                appendFilters(predicates, rightPlanProbeCopy);
                context.addPlan(newSubgraph, std::move(rightPlanProbeCopy));
            }
        }
    }
}

static bool isExpressionNewlyMatched(const std::vector<SubqueryGraph>& prevs,
    const SubqueryGraph& newSubgraph, const std::shared_ptr<Expression>& expression) {
    auto collector = DependentVarNameCollector();
    collector.visit(expression);
    auto variables = collector.getVarNames();
    for (auto& prev : prevs) {
        if (prev.containAllVariables(variables)) {
            return false; // matched in prev subgraph
        }
    }
    return newSubgraph.containAllVariables(variables);
}

expression_vector Planner::getNewlyMatchedExprs(const std::vector<SubqueryGraph>& prevs,
    const SubqueryGraph& new_, const expression_vector& exprs) {
    expression_vector result;
    for (auto& expr : exprs) {
        if (isExpressionNewlyMatched(prevs, new_, expr)) {
            result.push_back(expr);
        }
    }
    return result;
}

expression_vector Planner::getNewlyMatchedExprs(const SubqueryGraph& prev,
    const SubqueryGraph& new_, const expression_vector& exprs) {
    return getNewlyMatchedExprs(std::vector<SubqueryGraph>{prev}, new_, exprs);
}

expression_vector Planner::getNewlyMatchedExprs(const SubqueryGraph& leftPrev,
    const SubqueryGraph& rightPrev, const SubqueryGraph& new_, const expression_vector& exprs) {
    return getNewlyMatchedExprs(std::vector<SubqueryGraph>{leftPrev, rightPrev}, new_, exprs);
}

} // namespace planner
} // namespace lbug
