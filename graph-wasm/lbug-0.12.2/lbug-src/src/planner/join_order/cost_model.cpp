#include "planner/join_order/cost_model.h"

#include "common/constants.h"
#include "planner/join_order/join_order_util.h"
#include "planner/operator/logical_hash_join.h"

using namespace lbug::common;

namespace lbug {
namespace planner {

uint64_t CostModel::computeExtendCost(const LogicalPlan& childPlan) {
    return childPlan.getCost() + childPlan.getCardinality();
}

uint64_t CostModel::computeHashJoinCost(const std::vector<binder::expression_pair>& joinConditions,
    const LogicalPlan& probe, const LogicalPlan& build) {
    return computeHashJoinCost(LogicalHashJoin::getJoinNodeIDs(joinConditions), probe, build);
}

uint64_t CostModel::computeHashJoinCost(const binder::expression_vector& joinNodeIDs,
    const LogicalPlan& probe, const LogicalPlan& build) {
    uint64_t cost = 0ul;
    cost += probe.getCost();
    cost += build.getCost();
    cost += probe.getCardinality();
    cost += PlannerKnobs::BUILD_PENALTY *
            JoinOrderUtil::getJoinKeysFlatCardinality(joinNodeIDs, build.getLastOperatorRef());
    return cost;
}

uint64_t CostModel::computeMarkJoinCost(const std::vector<binder::expression_pair>& joinConditions,
    const LogicalPlan& probe, const LogicalPlan& build) {
    return computeMarkJoinCost(LogicalHashJoin::getJoinNodeIDs(joinConditions), probe, build);
}

uint64_t CostModel::computeMarkJoinCost(const binder::expression_vector& joinNodeIDs,
    const LogicalPlan& probe, const LogicalPlan& build) {
    return computeHashJoinCost(joinNodeIDs, probe, build);
}

uint64_t CostModel::computeIntersectCost(const LogicalPlan& probePlan,
    const std::vector<LogicalPlan>& buildPlans) {
    uint64_t cost = 0ul;
    cost += probePlan.getCost();
    // TODO(Xiyang): think of how to calculate intersect cost such that it will be picked in worst
    // case.
    cost += probePlan.getCardinality();
    for (auto& buildPlan : buildPlans) {
        KU_ASSERT(buildPlan.getCardinality() >= 1);
        cost += buildPlan.getCost();
    }
    return cost;
}

} // namespace planner
} // namespace lbug
