#pragma once

#include "common/cast.h"
#include "common/enums/clause_type.h"
#include "parser/expression/parsed_expression.h"

namespace lbug {
namespace parser {

class ReadingClause {
public:
    explicit ReadingClause(common::ClauseType clauseType) : clauseType{clauseType} {};
    virtual ~ReadingClause() = default;

    common::ClauseType getClauseType() const { return clauseType; }

    void setWherePredicate(std::unique_ptr<ParsedExpression> expression) {
        wherePredicate = std::move(expression);
    }
    bool hasWherePredicate() const { return wherePredicate != nullptr; }
    const ParsedExpression* getWherePredicate() const { return wherePredicate.get(); }

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }

private:
    common::ClauseType clauseType;
    std::unique_ptr<ParsedExpression> wherePredicate;
};
} // namespace parser
} // namespace lbug
