#include "planner/operator/logical_accumulate.h"

#include "planner/operator/factorization/flatten_resolver.h"
#include "planner/operator/factorization/sink_util.h"

namespace lbug {
namespace planner {

void LogicalAccumulate::computeFactorizedSchema() {
    createEmptySchema();
    auto childSchema = children[0]->getSchema();
    SinkOperatorUtil::recomputeSchema(*childSchema, getPayloads(), *schema);
    if (mark != nullptr) {
        auto groupPos = schema->createGroup();
        schema->setGroupAsSingleState(groupPos);
        schema->insertToGroupAndScope(mark, groupPos);
    }
}

void LogicalAccumulate::computeFlatSchema() {
    copyChildSchema(0);
    if (mark != nullptr) {
        schema->insertToGroupAndScope(mark, 0);
    }
}

f_group_pos_set LogicalAccumulate::getGroupPositionsToFlatten() const {
    f_group_pos_set result;
    auto childSchema = children[0]->getSchema();
    return FlattenAll::getGroupsPosToFlatten(flatExprs, *childSchema);
}

} // namespace planner
} // namespace lbug
