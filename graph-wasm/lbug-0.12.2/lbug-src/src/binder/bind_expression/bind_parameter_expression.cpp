#include "binder/expression/parameter_expression.h"
#include "binder/expression_binder.h"
#include "common/exception/binder.h"
#include "parser/expression/parsed_parameter_expression.h"

using namespace lbug::common;
using namespace lbug::parser;

namespace lbug {
namespace binder {

std::shared_ptr<Expression> ExpressionBinder::bindParameterExpression(
    const ParsedExpression& parsedExpression) {
    auto& parsedParameterExpression = parsedExpression.constCast<ParsedParameterExpression>();
    auto parameterName = parsedParameterExpression.getParameterName();
    if (knownParameters.contains(parameterName)) {
        return make_shared<ParameterExpression>(parameterName, *knownParameters.at(parameterName));
    }
    // LCOV_EXCL_START
    throw BinderException(
        stringFormat("Cannot find parameter {}. This should not happen.", parameterName));
    // LCOV_EXCL_STOP
}

} // namespace binder
} // namespace lbug
