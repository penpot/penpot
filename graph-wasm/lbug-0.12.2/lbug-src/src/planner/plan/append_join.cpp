#include "planner/join_order/cost_model.h"
#include "planner/operator/logical_hash_join.h"
#include "planner/operator/logical_intersect.h"
#include "planner/planner.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace planner {

void Planner::appendHashJoin(const expression_vector& joinNodeIDs, JoinType joinType,
    LogicalPlan& probePlan, LogicalPlan& buildPlan, LogicalPlan& resultPlan) {
    appendHashJoin(joinNodeIDs, joinType, nullptr /* mark */, probePlan, buildPlan, resultPlan);
}

void Planner::appendHashJoin(const expression_vector& joinNodeIDs, JoinType joinType,
    std::shared_ptr<Expression> mark, LogicalPlan& probePlan, LogicalPlan& buildPlan,
    LogicalPlan& resultPlan) {
    std::vector<join_condition_t> joinConditions;
    for (auto& joinNodeID : joinNodeIDs) {
        joinConditions.emplace_back(joinNodeID, joinNodeID);
    }
    appendHashJoin(joinConditions, joinType, mark, probePlan, buildPlan, resultPlan);
}

void Planner::appendHashJoin(const std::vector<expression_pair>& joinConditions, JoinType joinType,
    std::shared_ptr<Expression> mark, LogicalPlan& probePlan, LogicalPlan& buildPlan,
    LogicalPlan& resultPlan) {
    auto hashJoin = make_shared<LogicalHashJoin>(joinConditions, joinType, mark,
        probePlan.getLastOperator(), buildPlan.getLastOperator());
    // Apply flattening to probe side
    auto groupsPosToFlattenOnProbeSide = hashJoin->getGroupsPosToFlattenOnProbeSide();
    appendFlattens(groupsPosToFlattenOnProbeSide, probePlan);
    hashJoin->setChild(0, probePlan.getLastOperator());
    // Apply flattening to build side
    appendFlattens(hashJoin->getGroupsPosToFlattenOnBuildSide(), buildPlan);
    hashJoin->setChild(1, buildPlan.getLastOperator());
    hashJoin->computeFactorizedSchema();
    // Check for sip
    if (probePlan.getCardinality() > buildPlan.getCardinality() * PlannerKnobs::SIP_RATIO) {
        hashJoin->getSIPInfoUnsafe().position = SemiMaskPosition::PROHIBIT_PROBE_TO_BUILD;
    }
    // Update cost
    hashJoin->setCardinality(cardinalityEstimator.estimateHashJoin(joinConditions,
        probePlan.getLastOperatorRef(), buildPlan.getLastOperatorRef()));
    resultPlan.setCost(CostModel::computeHashJoinCost(joinConditions, probePlan, buildPlan));
    resultPlan.setLastOperator(std::move(hashJoin));
}

void Planner::appendAccHashJoin(const std::vector<binder::expression_pair>& joinConditions,
    JoinType joinType, std::shared_ptr<Expression> mark, LogicalPlan& probePlan,
    LogicalPlan& buildPlan, LogicalPlan& resultPlan) {
    KU_ASSERT(probePlan.hasUpdate());
    tryAppendAccumulate(probePlan);
    appendHashJoin(joinConditions, joinType, mark, probePlan, buildPlan, resultPlan);
    auto& sipInfo = probePlan.getLastOperator()->cast<LogicalHashJoin>().getSIPInfoUnsafe();
    sipInfo.direction = SIPDirection::PROBE_TO_BUILD;
}

void Planner::appendMarkJoin(const expression_vector& joinNodeIDs,
    const std::shared_ptr<Expression>& mark, LogicalPlan& probePlan, LogicalPlan& buildPlan,
    LogicalPlan& resultPlan) {
    std::vector<join_condition_t> joinConditions;
    for (auto& joinNodeID : joinNodeIDs) {
        joinConditions.emplace_back(joinNodeID, joinNodeID);
    }
    appendMarkJoin(joinConditions, mark, probePlan, buildPlan, resultPlan);
}

void Planner::appendMarkJoin(const std::vector<expression_pair>& joinConditions,
    const std::shared_ptr<Expression>& mark, LogicalPlan& probePlan, LogicalPlan& buildPlan,
    LogicalPlan& resultPlan) {
    auto hashJoin = make_shared<LogicalHashJoin>(joinConditions, JoinType::MARK, mark,
        probePlan.getLastOperator(), buildPlan.getLastOperator());
    // Apply flattening to probe side
    appendFlattens(hashJoin->getGroupsPosToFlattenOnProbeSide(), probePlan);
    hashJoin->setChild(0, probePlan.getLastOperator());
    // Apply flattening to build side
    appendFlattens(hashJoin->getGroupsPosToFlattenOnBuildSide(), buildPlan);
    hashJoin->setChild(1, buildPlan.getLastOperator());
    hashJoin->computeFactorizedSchema();
    // update cost. Mark join does not change cardinality.
    hashJoin->setCardinality(probePlan.getCardinality());
    resultPlan.setCost(CostModel::computeMarkJoinCost(joinConditions, probePlan, buildPlan));
    resultPlan.setLastOperator(std::move(hashJoin));
}

void Planner::appendIntersect(const std::shared_ptr<Expression>& intersectNodeID,
    expression_vector& boundNodeIDs, LogicalPlan& probePlan, std::vector<LogicalPlan>& buildPlans) {
    KU_ASSERT(boundNodeIDs.size() == buildPlans.size());
    std::vector<std::shared_ptr<LogicalOperator>> buildChildren;
    expression_vector keyNodeIDs;
    for (auto i = 0u; i < buildPlans.size(); ++i) {
        keyNodeIDs.push_back(boundNodeIDs[i]);
        buildChildren.push_back(buildPlans[i].getLastOperator());
    }
    auto intersect = make_shared<LogicalIntersect>(intersectNodeID, std::move(keyNodeIDs),
        probePlan.getLastOperator(), std::move(buildChildren));
    appendFlattens(intersect->getGroupsPosToFlattenOnProbeSide(), probePlan);
    intersect->setChild(0, probePlan.getLastOperator());
    for (auto i = 0u; i < buildPlans.size(); ++i) {
        appendFlattens(intersect->getGroupsPosToFlattenOnBuildSide(i), buildPlans[i]);
        intersect->setChild(i + 1, buildPlans[i].getLastOperator());
        auto ratio = probePlan.getCardinality() / buildPlans[i].getCardinality();
        if (ratio > PlannerKnobs::SIP_RATIO) {
            intersect->getSIPInfoUnsafe().position = SemiMaskPosition::PROHIBIT;
        }
    }
    intersect->computeFactorizedSchema();
    // update cost
    std::vector<LogicalOperator*> buildOps;
    for (const auto& plan : buildPlans) {
        buildOps.push_back(plan.getLastOperator().get());
    }
    intersect->setCardinality(cardinalityEstimator.estimateIntersect(boundNodeIDs,
        probePlan.getLastOperatorRef(), buildOps));
    probePlan.setCost(CostModel::computeIntersectCost(probePlan, buildPlans));
    probePlan.setLastOperator(std::move(intersect));
}

} // namespace planner
} // namespace lbug
