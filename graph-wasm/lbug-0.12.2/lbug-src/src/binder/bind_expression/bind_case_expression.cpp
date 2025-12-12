#include "binder/binder.h"
#include "binder/expression/case_expression.h"
#include "binder/expression/expression_util.h"
#include "binder/expression_binder.h"
#include "parser/expression/parsed_case_expression.h"

using namespace lbug::common;
using namespace lbug::parser;

namespace lbug {
namespace binder {

std::shared_ptr<Expression> ExpressionBinder::bindCaseExpression(
    const ParsedExpression& parsedExpression) {
    auto& parsedCaseExpression = parsedExpression.constCast<ParsedCaseExpression>();
    auto resultType = LogicalType::ANY();
    // Resolve result type by checking each then expression type.
    for (auto i = 0u; i < parsedCaseExpression.getNumCaseAlternative(); ++i) {
        auto alternative = parsedCaseExpression.getCaseAlternative(i);
        auto boundThen = bindExpression(*alternative->thenExpression);
        if (boundThen->getDataType().getLogicalTypeID() != LogicalTypeID::ANY) {
            resultType = boundThen->getDataType().copy();
        }
    }
    // Resolve result type by else expression if above resolving fails.
    if (resultType.getLogicalTypeID() == LogicalTypeID::ANY &&
        parsedCaseExpression.hasElseExpression()) {
        auto elseExpression = bindExpression(*parsedCaseExpression.getElseExpression());
        resultType = elseExpression->getDataType().copy();
    }
    auto name = binder->getUniqueExpressionName(parsedExpression.getRawName());
    // bind ELSE ...
    std::shared_ptr<Expression> elseExpression;
    if (parsedCaseExpression.hasElseExpression()) {
        elseExpression = bindExpression(*parsedCaseExpression.getElseExpression());
    } else {
        elseExpression = createNullLiteralExpression();
    }
    elseExpression = implicitCastIfNecessary(elseExpression, resultType);
    auto boundCaseExpression =
        make_shared<CaseExpression>(resultType.copy(), std::move(elseExpression), name);
    // bind WHEN ... THEN ...
    if (parsedCaseExpression.hasCaseExpression()) {
        auto boundCase = bindExpression(*parsedCaseExpression.getCaseExpression());
        for (auto i = 0u; i < parsedCaseExpression.getNumCaseAlternative(); ++i) {
            auto caseAlternative = parsedCaseExpression.getCaseAlternative(i);
            auto boundWhen = bindExpression(*caseAlternative->whenExpression);
            boundWhen = implicitCastIfNecessary(boundWhen, boundCase->dataType);
            // rewrite "CASE a.age WHEN 1" as "CASE WHEN a.age = 1"
            if (ExpressionUtil::isNullLiteral(*boundWhen)) {
                boundWhen = bindNullOperatorExpression(ExpressionType::IS_NULL,
                    expression_vector{boundWhen});
            } else {
                boundWhen = bindComparisonExpression(ExpressionType::EQUALS,
                    expression_vector{boundCase, boundWhen});
            }
            auto boundThen = bindExpression(*caseAlternative->thenExpression);
            boundThen = implicitCastIfNecessary(boundThen, resultType);
            boundCaseExpression->addCaseAlternative(boundWhen, boundThen);
        }
    } else {
        for (auto i = 0u; i < parsedCaseExpression.getNumCaseAlternative(); ++i) {
            auto caseAlternative = parsedCaseExpression.getCaseAlternative(i);
            auto boundWhen = bindExpression(*caseAlternative->whenExpression);
            boundWhen = implicitCastIfNecessary(boundWhen, LogicalType::BOOL());
            auto boundThen = bindExpression(*caseAlternative->thenExpression);
            boundThen = implicitCastIfNecessary(boundThen, resultType);
            boundCaseExpression->addCaseAlternative(boundWhen, boundThen);
        }
    }
    return boundCaseExpression;
}

} // namespace binder
} // namespace lbug
