#include "binder/expression/scalar_function_expression.h"
#include "binder/expression_binder.h"
#include "function/boolean/vector_boolean_functions.h"

using namespace lbug::common;
using namespace lbug::parser;
using namespace lbug::function;

namespace lbug {
namespace binder {

std::shared_ptr<Expression> ExpressionBinder::bindBooleanExpression(
    const ParsedExpression& parsedExpression) {
    expression_vector children;
    for (auto i = 0u; i < parsedExpression.getNumChildren(); ++i) {
        children.push_back(bindExpression(*parsedExpression.getChild(i)));
    }
    return bindBooleanExpression(parsedExpression.getExpressionType(), children);
}

std::shared_ptr<Expression> ExpressionBinder::bindBooleanExpression(ExpressionType expressionType,
    const expression_vector& children) {
    expression_vector childrenAfterCast;
    std::vector<LogicalTypeID> inputTypeIDs;
    for (auto& child : children) {
        childrenAfterCast.push_back(implicitCastIfNecessary(child, LogicalType::BOOL()));
        inputTypeIDs.push_back(LogicalTypeID::BOOL);
    }
    auto functionName = ExpressionTypeUtil::toString(expressionType);
    scalar_func_exec_t execFunc;
    VectorBooleanFunction::bindExecFunction(expressionType, childrenAfterCast, execFunc);
    scalar_func_select_t selectFunc;
    VectorBooleanFunction::bindSelectFunction(expressionType, childrenAfterCast, selectFunc);
    auto bindData = std::make_unique<FunctionBindData>(LogicalType::BOOL());
    auto uniqueExpressionName =
        ScalarFunctionExpression::getUniqueName(functionName, childrenAfterCast);
    auto func = std::make_unique<ScalarFunction>(functionName, inputTypeIDs, LogicalTypeID::BOOL,
        execFunc, selectFunc);
    return std::make_shared<ScalarFunctionExpression>(expressionType, std::move(func),
        std::move(bindData), std::move(childrenAfterCast), uniqueExpressionName);
}

std::shared_ptr<Expression> ExpressionBinder::combineBooleanExpressions(
    ExpressionType expressionType, std::shared_ptr<Expression> left,
    std::shared_ptr<Expression> right) {
    if (left == nullptr) {
        return right;
    } else if (right == nullptr) {
        return left;
    } else {
        return bindBooleanExpression(expressionType,
            expression_vector{std::move(left), std::move(right)});
    }
}

} // namespace binder
} // namespace lbug
