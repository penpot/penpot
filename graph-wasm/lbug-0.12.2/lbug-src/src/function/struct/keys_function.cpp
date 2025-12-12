#include "binder/expression/expression_util.h"
#include "binder/expression/literal_expression.h"
#include "binder/expression/scalar_function_expression.h"
#include "binder/expression_binder.h"
#include "function/rewrite_function.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace function {

static std::shared_ptr<Expression> rewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    auto argument = input.arguments[0].get();
    auto expressionBinder = input.expressionBinder;
    if (ExpressionUtil::isNullLiteral(*argument)) {
        return expressionBinder->createNullLiteralExpression();
    }
    auto uniqueExpressionName =
        ScalarFunctionExpression::getUniqueName(KeysFunctions::name, input.arguments);
    const auto& resultType = LogicalType::LIST(LogicalType::STRING());
    auto fields = common::StructType::getFieldNames(input.arguments[0]->dataType);
    std::vector<std::unique_ptr<Value>> children;
    for (auto field : fields) {
        if (field == InternalKeyword::ID || field == InternalKeyword::LABEL ||
            field == InternalKeyword::SRC || field == InternalKeyword::DST) {
            continue;
        }
        children.push_back(std::make_unique<Value>(field));
    }
    auto resultExpr = std::make_shared<binder::LiteralExpression>(
        Value{resultType.copy(), std::move(children)}, std::move(uniqueExpressionName));
    return resultExpr;
}

static std::unique_ptr<Function> getKeysFunction(LogicalTypeID logicalTypeID) {
    return std::make_unique<function::RewriteFunction>(KeysFunctions::name,
        std::vector<LogicalTypeID>{logicalTypeID}, rewriteFunc);
}

function_set KeysFunctions::getFunctionSet() {
    function_set functions;
    auto inputTypeIDs = std::vector<LogicalTypeID>{LogicalTypeID::NODE, LogicalTypeID::REL};
    for (auto inputTypeID : inputTypeIDs) {
        functions.push_back(getKeysFunction(inputTypeID));
    }
    return functions;
}

} // namespace function
} // namespace lbug
