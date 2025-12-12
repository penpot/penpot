#include "planner/operator/logical_order_by.h"

#include "binder/expression/expression_util.h"
#include "planner/operator/factorization/flatten_resolver.h"
#include "planner/operator/factorization/sink_util.h"

using namespace lbug::binder;

namespace lbug {
namespace planner {

f_group_pos_set LogicalOrderBy::getGroupsPosToFlatten() {
    // We only allow orderby key(s) to be unflat, if they are all part of the same factorization
    // group and there is no other factorized group in the schema, so any payload is also unflat
    // and part of the same factorization group. The rationale for this limitation is this: (1)
    // to keep both the frontend and orderby operators simpler, we want order by to not change
    // the schema, so the input and output of order by should have the same factorization
    // structure. (2) Because orderby needs to flatten the keys to sort, if a key column that is
    // unflat is the input, we need to somehow flatten it in the factorized table. However
    // whenever we can we want to avoid adding an explicit flatten operator as this makes us
    // fall back to tuple-at-a-time processing. However in the specified limited case, we can
    // give factorized table a set of unflat vectors (all in the same datachunk/factorization
    // group), sort the table, and scan into unflat vectors, so the schema remains the same. In
    // more complicated cases, e.g., when there are 2 factorization groups, FactorizedTable
    // cannot read back a flat column into an unflat std::vector.
    auto childSchema = children[0]->getSchema();
    if (childSchema->getNumGroups() > 1) {
        return FlattenAll::getGroupsPosToFlatten(expressionsToOrderBy, *childSchema);
    }
    return f_group_pos_set{};
}

void LogicalOrderBy::computeFactorizedSchema() {
    createEmptySchema();
    auto childSchema = children[0]->getSchema();
    SinkOperatorUtil::recomputeSchema(*childSchema, childSchema->getExpressionsInScope(), *schema);
}

void LogicalOrderBy::computeFlatSchema() {
    createEmptySchema();
    schema->createGroup();
    for (auto& expression : children[0]->getSchema()->getExpressionsInScope()) {
        schema->insertToGroupAndScope(expression, 0);
    }
}

std::string LogicalOrderBy::getExpressionsForPrinting() const {
    auto result = ExpressionUtil::toString(expressionsToOrderBy) + " ";
    if (hasSkipNum() && ExpressionUtil::canEvaluateAsLiteral(*skipNum)) {
        result += "SKIP " + std::to_string(ExpressionUtil::evaluateAsSkipLimit(*skipNum));
    }
    if (hasLimitNum() && ExpressionUtil::canEvaluateAsLiteral(*limitNum)) {
        result += "LIMIT " + std::to_string(ExpressionUtil::evaluateAsSkipLimit(*limitNum));
    }
    return result;
}

} // namespace planner
} // namespace lbug
