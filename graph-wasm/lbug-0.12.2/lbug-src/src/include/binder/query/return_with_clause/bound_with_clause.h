#pragma once

#include "bound_return_clause.h"

namespace lbug {
namespace binder {

class BoundWithClause final : public BoundReturnClause {
public:
    explicit BoundWithClause(BoundProjectionBody projectionBody)
        : BoundReturnClause{std::move(projectionBody)} {}

    void setWhereExpression(std::shared_ptr<Expression> expression) {
        whereExpression = std::move(expression);
    }
    bool hasWhereExpression() const { return whereExpression != nullptr; }
    std::shared_ptr<Expression> getWhereExpression() const { return whereExpression; }

private:
    std::shared_ptr<Expression> whereExpression;
};

} // namespace binder
} // namespace lbug
