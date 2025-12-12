#include "binder/binder.h"
#include "binder/expression/literal_expression.h"
#include "binder/expression_binder.h"
#include "parser/expression/parsed_literal_expression.h"

using namespace lbug::parser;
using namespace lbug::common;

namespace lbug {
namespace binder {

std::shared_ptr<Expression> ExpressionBinder::bindLiteralExpression(
    const ParsedExpression& parsedExpression) const {
    auto& literalExpression = parsedExpression.constCast<ParsedLiteralExpression>();
    auto value = literalExpression.getValue();
    if (value.isNull()) {
        return createNullLiteralExpression(value);
    }
    return createLiteralExpression(value);
}

std::shared_ptr<Expression> ExpressionBinder::createLiteralExpression(const Value& value) const {
    auto uniqueName = binder->getUniqueExpressionName(value.toString());
    return std::make_unique<LiteralExpression>(value, uniqueName);
}

std::shared_ptr<Expression> ExpressionBinder::createLiteralExpression(
    const std::string& strVal) const {
    return createLiteralExpression(Value(strVal));
}

std::shared_ptr<Expression> ExpressionBinder::createNullLiteralExpression() const {
    return make_shared<LiteralExpression>(Value::createNullValue(),
        binder->getUniqueExpressionName("NULL"));
}

std::shared_ptr<Expression> ExpressionBinder::createNullLiteralExpression(
    const Value& value) const {
    return make_shared<LiteralExpression>(value, binder->getUniqueExpressionName("NULL"));
}

} // namespace binder
} // namespace lbug
