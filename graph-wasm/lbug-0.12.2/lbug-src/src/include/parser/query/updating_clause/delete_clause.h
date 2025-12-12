#pragma once

#include "common/enums/delete_type.h"
#include "parser/expression/parsed_expression.h"
#include "updating_clause.h"

namespace lbug {
namespace parser {

class DeleteClause final : public UpdatingClause {
public:
    explicit DeleteClause(common::DeleteNodeType deleteType)
        : UpdatingClause{common::ClauseType::DELETE_}, deleteType{deleteType} {};

    void addExpression(std::unique_ptr<ParsedExpression> expression) {
        expressions.push_back(std::move(expression));
    }
    common::DeleteNodeType getDeleteClauseType() const { return deleteType; }
    uint32_t getNumExpressions() const { return expressions.size(); }
    ParsedExpression* getExpression(uint32_t idx) const { return expressions[idx].get(); }

private:
    common::DeleteNodeType deleteType;
    parsed_expr_vector expressions;
};

} // namespace parser
} // namespace lbug
