#include "binder/expression/scalar_function_expression.h"

#include "binder/expression/expression_util.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

std::string ScalarFunctionExpression::toStringInternal() const {
    if (function->name.starts_with("CAST")) {
        return stringFormat("CAST({}, {})", ExpressionUtil::toString(children),
            bindData->resultType.toString());
    }
    return stringFormat("{}({})", function->name, ExpressionUtil::toString(children));
}

std::string ScalarFunctionExpression::getUniqueName(const std::string& functionName,
    const expression_vector& children) {
    return stringFormat("{}({})", functionName, ExpressionUtil::getUniqueName(children));
}

} // namespace binder
} // namespace lbug
