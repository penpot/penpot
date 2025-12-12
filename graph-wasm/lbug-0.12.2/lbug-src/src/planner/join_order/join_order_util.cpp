#include "planner/join_order/join_order_util.h"

namespace lbug {
namespace planner {

uint64_t JoinOrderUtil::getJoinKeysFlatCardinality(const binder::expression_vector& joinNodeIDs,
    const LogicalOperator& buildOp) {
    auto schema = buildOp.getSchema();
    f_group_pos_set unFlatGroupsPos;
    for (auto& joinID : joinNodeIDs) {
        auto groupPos = schema->getGroupPos(*joinID);
        if (!schema->getGroup(groupPos)->isFlat()) {
            unFlatGroupsPos.insert(groupPos);
        }
    }
    auto cardinality = buildOp.getCardinality();
    for (auto groupPos : unFlatGroupsPos) {
        cardinality *= schema->getGroup(groupPos)->getMultiplier();
    }
    return cardinality;
}

} // namespace planner
} // namespace lbug
