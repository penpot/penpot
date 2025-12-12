#include "binder/binder.h"
#include "binder/expression/expression_util.h"
#include "binder/expression/lambda_expression.h"
#include "parser/expression/parsed_lambda_expression.h"

using namespace lbug::common;
using namespace lbug::parser;

namespace lbug {
namespace binder {

void ExpressionBinder::bindLambdaExpression(const Expression& lambdaInput,
    Expression& lambdaExpr) const {
    ExpressionUtil::validateDataType(lambdaInput, LogicalTypeID::LIST);
    auto& listChildType = ListType::getChildType(lambdaInput.getDataType());
    auto& boundLambdaExpr = lambdaExpr.cast<LambdaExpression>();
    auto& parsedLambdaExpr =
        boundLambdaExpr.getParsedLambdaExpr()->constCast<ParsedLambdaExpression>();
    auto prevScope = binder->saveScope();
    for (auto& varName : parsedLambdaExpr.getVarNames()) {
        binder->createVariable(varName, listChildType);
    }
    auto funcExpr =
        binder->getExpressionBinder()->bindExpression(*parsedLambdaExpr.getFunctionExpr());
    binder->restoreScope(std::move(prevScope));
    boundLambdaExpr.cast(funcExpr->getDataType().copy());
    boundLambdaExpr.setFunctionExpr(std::move(funcExpr));
}

std::shared_ptr<Expression> ExpressionBinder::bindLambdaExpression(
    const parser::ParsedExpression& parsedExpr) const {
    auto uniqueName = getUniqueName(parsedExpr.getRawName());
    return std::make_shared<LambdaExpression>(parsedExpr.copy(), uniqueName);
}

} // namespace binder
} // namespace lbug
