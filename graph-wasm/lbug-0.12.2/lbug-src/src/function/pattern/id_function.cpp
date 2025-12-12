#include "binder/expression/expression_util.h"
#include "binder/expression/node_expression.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression_binder.h"
#include "function/rewrite_function.h"
#include "function/schema/vector_node_rel_functions.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace function {

static std::shared_ptr<Expression> rewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto param = input.arguments[0].get();
    if (ExpressionUtil::isNodePattern(*param)) {
        auto node = param->constPtrCast<NodeExpression>();
        return node->getInternalID();
    }
    if (ExpressionUtil::isRelPattern(*param)) {
        auto rel = param->constPtrCast<RelExpression>();
        return rel->getPropertyExpression(InternalKeyword::ID);
    }
    // Bind as struct_extract(param, "_id")
    auto extractKey = input.expressionBinder->createLiteralExpression(InternalKeyword::ID);
    return input.expressionBinder->bindScalarFunctionExpression({input.arguments[0], extractKey},
        StructExtractFunctions::name);
}

function_set IDFunction::getFunctionSet() {
    function_set functionSet;
    auto inputTypes =
        std::vector<LogicalTypeID>{LogicalTypeID::NODE, LogicalTypeID::REL, LogicalTypeID::STRUCT};
    for (auto& inputType : inputTypes) {
        auto function = std::make_unique<RewriteFunction>(name,
            std::vector<LogicalTypeID>{inputType}, rewriteFunc);
        functionSet.push_back(std::move(function));
    }
    return functionSet;
}

} // namespace function
} // namespace lbug
