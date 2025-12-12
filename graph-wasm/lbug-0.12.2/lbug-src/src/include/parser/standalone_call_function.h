#pragma once

#include "parser/expression/parsed_expression.h"
#include "parser/statement.h"

namespace lbug {
namespace parser {

class StandaloneCallFunction : public Statement {
public:
    explicit StandaloneCallFunction(std::unique_ptr<ParsedExpression> functionExpression)
        : Statement{common::StatementType::STANDALONE_CALL_FUNCTION},
          functionExpression{std::move(functionExpression)} {}

    const ParsedExpression* getFunctionExpression() const { return functionExpression.get(); }

private:
    std::unique_ptr<ParsedExpression> functionExpression;
};

} // namespace parser
} // namespace lbug
