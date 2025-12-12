#pragma once

#include "parser/query/reading_clause/reading_clause.h"
#include "yield_variable.h"

namespace lbug {
namespace parser {

class InQueryCallClause final : public ReadingClause {
    static constexpr common::ClauseType clauseType_ = common::ClauseType::IN_QUERY_CALL;

public:
    InQueryCallClause(std::unique_ptr<ParsedExpression> functionExpression,
        std::vector<YieldVariable> yieldClause)
        : ReadingClause{clauseType_}, functionExpression{std::move(functionExpression)},
          yieldVariables{std::move(yieldClause)} {}

    const ParsedExpression* getFunctionExpression() const { return functionExpression.get(); }

    const std::vector<YieldVariable>& getYieldVariables() const { return yieldVariables; }

private:
    std::unique_ptr<ParsedExpression> functionExpression;
    std::vector<YieldVariable> yieldVariables;
};

} // namespace parser
} // namespace lbug
