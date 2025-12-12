#pragma once

#include "common/enums/expression_type.h"
#include "parsed_expression.h"

namespace lbug {
namespace parser {

class ParsedLambdaExpression : public ParsedExpression {
    static constexpr const common::ExpressionType type_ = common::ExpressionType::LAMBDA;

public:
    ParsedLambdaExpression(std::vector<std::string> varNames,
        std::unique_ptr<ParsedExpression> expr, std::string rawName)
        : ParsedExpression{type_, rawName}, varNames{std::move(varNames)},
          functionExpr{std::move(expr)} {}

    std::vector<std::string> getVarNames() const { return varNames; }

    ParsedExpression* getFunctionExpr() const { return functionExpr.get(); }

    std::unique_ptr<ParsedExpression> copy() const override {
        return std::make_unique<ParsedLambdaExpression>(varNames, functionExpr->copy(), rawName);
    }

private:
    std::vector<std::string> varNames;
    std::unique_ptr<ParsedExpression> functionExpr;
};

} // namespace parser
} // namespace lbug
