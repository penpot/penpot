#include "binder/expression/expression_util.h"
#include "binder/expression/rel_expression.h"
#include "binder/expression_binder.h"
#include "function/rewrite_function.h"
#include "function/schema/vector_node_rel_functions.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace function {

static std::shared_ptr<Expression> startRewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto param = input.arguments[0].get();
    if (ExpressionUtil::isRelPattern(*param)) {
        return param->constCast<RelExpression>().getSrcNode();
    }
    auto extractKey = input.expressionBinder->createLiteralExpression(InternalKeyword::SRC);
    return input.expressionBinder->bindScalarFunctionExpression({input.arguments[0], extractKey},
        StructExtractFunctions::name);
}

function_set StartNodeFunction::getFunctionSet() {
    function_set set;
    auto function = std::make_unique<RewriteFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::REL}, startRewriteFunc);
    set.push_back(std::move(function));
    return set;
}

static std::shared_ptr<Expression> endRewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto param = input.arguments[0].get();
    if (ExpressionUtil::isRelPattern(*param)) {
        return param->constCast<RelExpression>().getDstNode();
    }
    auto extractKey = input.expressionBinder->createLiteralExpression(InternalKeyword::DST);
    return input.expressionBinder->bindScalarFunctionExpression({input.arguments[0], extractKey},
        StructExtractFunctions::name);
}

function_set EndNodeFunction::getFunctionSet() {
    function_set set;
    auto function = std::make_unique<RewriteFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::REL}, endRewriteFunc);
    set.push_back(std::move(function));
    return set;
}

} // namespace function
} // namespace lbug
