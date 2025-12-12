#pragma once

#include "expression.h"
#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace binder {

class LambdaExpression final : public Expression {
    static constexpr common::ExpressionType type_ = common::ExpressionType::LAMBDA;

public:
    LambdaExpression(std::unique_ptr<parser::ParsedExpression> parsedLambdaExpr,
        std::string uniqueName)
        : Expression{type_, common::LogicalType::ANY(), std::move(uniqueName)},
          parsedLambdaExpr{std::move(parsedLambdaExpr)} {}

    void cast(const common::LogicalType& type_) override {
        KU_ASSERT(dataType.getLogicalTypeID() == common::LogicalTypeID::ANY);
        dataType = type_.copy();
    }

    parser::ParsedExpression* getParsedLambdaExpr() const { return parsedLambdaExpr.get(); }

    void setFunctionExpr(std::shared_ptr<Expression> expr) { functionExpr = std::move(expr); }
    std::shared_ptr<Expression> getFunctionExpr() const { return functionExpr; }

    std::string toStringInternal() const override { return parsedLambdaExpr->toString(); }

private:
    std::unique_ptr<parser::ParsedExpression> parsedLambdaExpr;
    std::shared_ptr<Expression> functionExpr;
};

} // namespace binder
} // namespace lbug
