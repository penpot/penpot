#include "binder/expression/scalar_function_expression.h"
#include "binder/expression_binder.h"
#include "function/null/vector_null_functions.h"

using namespace lbug::common;
using namespace lbug::parser;
using namespace lbug::function;

namespace lbug {
namespace binder {

std::shared_ptr<Expression> ExpressionBinder::bindNullOperatorExpression(
    const ParsedExpression& parsedExpression) {
    expression_vector children;
    for (auto i = 0u; i < parsedExpression.getNumChildren(); ++i) {
        children.push_back(bindExpression(*parsedExpression.getChild(i)));
    }
    return bindNullOperatorExpression(parsedExpression.getExpressionType(), children);
}

std::shared_ptr<Expression> ExpressionBinder::bindNullOperatorExpression(
    ExpressionType expressionType, const expression_vector& children) {
    expression_vector childrenAfterCast;
    std::vector<LogicalTypeID> inputTypeIDs;
    for (auto& child : children) {
        inputTypeIDs.push_back(child->getDataType().getLogicalTypeID());
        if (child->dataType.getLogicalTypeID() == LogicalTypeID::ANY) {
            childrenAfterCast.push_back(implicitCastIfNecessary(child, LogicalType::BOOL()));
        } else {
            childrenAfterCast.push_back(child);
        }
    }
    auto functionName = ExpressionTypeUtil::toString(expressionType);
    function::scalar_func_exec_t execFunc;
    function::VectorNullFunction::bindExecFunction(expressionType, childrenAfterCast, execFunc);
    function::scalar_func_select_t selectFunc;
    function::VectorNullFunction::bindSelectFunction(expressionType, childrenAfterCast, selectFunc);
    auto bindData = std::make_unique<function::FunctionBindData>(LogicalType::BOOL());
    auto uniqueExpressionName =
        ScalarFunctionExpression::getUniqueName(functionName, childrenAfterCast);
    auto func = std::make_unique<ScalarFunction>(functionName, inputTypeIDs, LogicalTypeID::BOOL,
        execFunc, selectFunc);
    return make_shared<ScalarFunctionExpression>(expressionType, std::move(func),
        std::move(bindData), std::move(childrenAfterCast), uniqueExpressionName);
}

} // namespace binder
} // namespace lbug
