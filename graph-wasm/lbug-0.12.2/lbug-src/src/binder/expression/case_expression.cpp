#include "binder/expression/case_expression.h"

namespace lbug {
namespace binder {

std::string CaseExpression::toStringInternal() const {
    std::string result = "CASE ";
    for (auto& caseAlternative : caseAlternatives) {
        result += "WHEN " + caseAlternative->whenExpression->toString() + " THEN " +
                  caseAlternative->thenExpression->toString();
    }
    result += " ELSE " + elseExpression->toString();
    return result;
}

} // namespace binder
} // namespace lbug
