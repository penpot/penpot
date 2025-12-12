#include "planner/operator/logical_distinct.h"

#include "binder/expression/expression_util.h"
#include "planner/operator/factorization/flatten_resolver.h"

namespace lbug {
namespace planner {

void LogicalDistinct::computeFactorizedSchema() {
    createEmptySchema();
    auto groupPos = schema->createGroup();
    for (auto& expression : getKeysAndPayloads()) {
        schema->insertToGroupAndScope(expression, groupPos);
    }
}

void LogicalDistinct::computeFlatSchema() {
    createEmptySchema();
    schema->createGroup();
    for (auto& expression : getKeysAndPayloads()) {
        schema->insertToGroupAndScope(expression, 0);
    }
}

f_group_pos_set LogicalDistinct::getGroupsPosToFlatten() {
    auto childSchema = children[0]->getSchema();
    return FlattenAll::getGroupsPosToFlatten(getKeysAndPayloads(), *childSchema);
}

std::string LogicalDistinct::getExpressionsForPrinting() const {
    return binder::ExpressionUtil::toString(getKeysAndPayloads());
}

binder::expression_vector LogicalDistinct::getKeysAndPayloads() const {
    binder::expression_vector result;
    result.insert(result.end(), keys.begin(), keys.end());
    result.insert(result.end(), payloads.begin(), payloads.end());
    return result;
}

} // namespace planner
} // namespace lbug
