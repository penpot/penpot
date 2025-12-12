#include "planner/operator/logical_limit.h"

#include "binder/expression/expression_util.h"
#include "planner/operator/factorization/flatten_resolver.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

std::string LogicalLimit::getExpressionsForPrinting() const {
    std::string result;
    if (hasSkipNum()) {
        result += "SKIP ";
        if (ExpressionUtil::canEvaluateAsLiteral(*skipNum)) {
            result += std::to_string(ExpressionUtil::evaluateAsSkipLimit(*skipNum));
        }
    }
    if (hasLimitNum()) {
        if (!result.empty()) {
            result += ",";
        }
        result += "LIMIT ";
        if (ExpressionUtil::canEvaluateAsLiteral(*limitNum)) {
            result += std::to_string(ExpressionUtil::evaluateAsSkipLimit(*limitNum));
        }
    }
    return result;
}

f_group_pos_set LogicalLimit::getGroupsPosToFlatten() {
    auto childSchema = children[0]->getSchema();
    return FlattenAllButOne::getGroupsPosToFlatten(childSchema->getGroupsPosInScope(),
        *childSchema);
}

f_group_pos LogicalLimit::getGroupPosToSelect() const {
    auto childSchema = children[0]->getSchema();
    auto groupsPosInScope = childSchema->getGroupsPosInScope();
    SchemaUtils::validateAtMostOneUnFlatGroup(groupsPosInScope, *childSchema);
    return SchemaUtils::getLeadingGroupPos(groupsPosInScope, *childSchema);
}

} // namespace planner
} // namespace lbug
