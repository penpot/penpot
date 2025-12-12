#include "binder/expression/case_expression.h"
#include "binder/expression/scalar_function_expression.h"
#include "binder/expression_binder.h"
#include "function/rewrite_function.h"
#include "function/utility/vector_utility_functions.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace function {

static std::shared_ptr<Expression> rewriteFunc(const RewriteFunctionBindInput& input) {
    KU_ASSERT(input.arguments.size() == 2);
    auto uniqueExpressionName =
        ScalarFunctionExpression::getUniqueName(NullIfFunction::name, input.arguments);
    const auto& resultType = input.arguments[0]->getDataType();
    auto caseExpression = std::make_shared<CaseExpression>(resultType.copy(), input.arguments[0],
        uniqueExpressionName);
    auto binder = input.expressionBinder;
    auto whenExpression = binder->bindComparisonExpression(ExpressionType::EQUALS, input.arguments);
    auto thenExpression = binder->createNullLiteralExpression();
    thenExpression = binder->implicitCastIfNecessary(thenExpression, resultType.copy());
    caseExpression->addCaseAlternative(whenExpression, thenExpression);
    return caseExpression;
}

function_set NullIfFunction::getFunctionSet() {
    function_set functionSet;
    for (auto typeID : LogicalTypeUtils::getAllValidLogicTypeIDs()) {
        functionSet.push_back(std::make_unique<RewriteFunction>(name,
            std::vector<LogicalTypeID>{typeID, typeID}, rewriteFunc));
    }
    return functionSet;
}

} // namespace function
} // namespace lbug
