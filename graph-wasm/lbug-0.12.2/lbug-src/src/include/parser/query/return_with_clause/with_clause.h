#pragma once

#include "return_clause.h"

namespace lbug {
namespace parser {

class WithClause : public ReturnClause {
public:
    explicit WithClause(ProjectionBody projectionBody) : ReturnClause{std::move(projectionBody)} {}
    DELETE_COPY_DEFAULT_MOVE(WithClause);

    inline void setWhereExpression(std::unique_ptr<ParsedExpression> expression) {
        whereExpression = std::move(expression);
    }

    inline bool hasWhereExpression() const { return whereExpression != nullptr; }

    inline ParsedExpression* getWhereExpression() const { return whereExpression.get(); }

private:
    std::unique_ptr<ParsedExpression> whereExpression;
};

} // namespace parser
} // namespace lbug
