#pragma once

#include "expression.h"

namespace lbug {
namespace binder {

struct CaseAlternative {
    std::shared_ptr<Expression> whenExpression;
    std::shared_ptr<Expression> thenExpression;

    CaseAlternative(std::shared_ptr<Expression> whenExpression,
        std::shared_ptr<Expression> thenExpression)
        : whenExpression{std::move(whenExpression)}, thenExpression{std::move(thenExpression)} {}
};

class CaseExpression final : public Expression {
    static constexpr common::ExpressionType expressionType_ = common::ExpressionType::CASE_ELSE;

public:
    CaseExpression(common::LogicalType dataType, std::shared_ptr<Expression> elseExpression,
        const std::string& name)
        : Expression{expressionType_, std::move(dataType), name},
          elseExpression{std::move(elseExpression)} {}

    void addCaseAlternative(std::shared_ptr<Expression> when, std::shared_ptr<Expression> then) {
        caseAlternatives.push_back(make_unique<CaseAlternative>(std::move(when), std::move(then)));
    }
    common::idx_t getNumCaseAlternatives() const { return caseAlternatives.size(); }
    CaseAlternative* getCaseAlternative(common::idx_t idx) const {
        return caseAlternatives[idx].get();
    }

    std::shared_ptr<Expression> getElseExpression() const { return elseExpression; }

    std::string toStringInternal() const override;

private:
    std::vector<std::unique_ptr<CaseAlternative>> caseAlternatives;
    std::shared_ptr<Expression> elseExpression;
};

} // namespace binder
} // namespace lbug
