#include "function/aggregate/count.h"

#include "binder/expression/expression_util.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/rel_expression.h"

using namespace lbug::common;
using namespace lbug::storage;
using namespace lbug::binder;

namespace lbug {
namespace function {

void CountFunction::updateAll(uint8_t* state_, ValueVector* input, uint64_t multiplicity,
    InMemOverflowBuffer* /*overflowBuffer*/) {
    auto state = reinterpret_cast<CountState*>(state_);
    state->count += multiplicity * input->countNonNull();
}

void CountFunction::paramRewriteFunc(expression_vector& arguments) {
    KU_ASSERT(arguments.size() == 1);
    if (ExpressionUtil::isNodePattern(*arguments[0])) {
        arguments[0] = arguments[0]->constCast<NodeExpression>().getInternalID();
    } else if (ExpressionUtil::isRelPattern(*arguments[0])) {
        arguments[0] = arguments[0]->constCast<RelExpression>().getInternalID();
    }
}

function_set CountFunction::getFunctionSet() {
    function_set result;
    for (auto& type : LogicalTypeUtils::getAllValidLogicTypeIDs()) {
        for (auto isDistinct : std::vector<bool>{true, false}) {
            auto func = AggregateFunctionUtils::getAggFunc<CountFunction>(name, type,
                LogicalTypeID::INT64, isDistinct, paramRewriteFunc);
            func->needToHandleNulls = true;
            result.push_back(std::move(func));
        }
    }
    return result;
}

} // namespace function
} // namespace lbug
