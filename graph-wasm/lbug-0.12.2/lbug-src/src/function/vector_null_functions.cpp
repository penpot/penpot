#include "function/null/vector_null_functions.h"

#include "common/exception/runtime.h"
#include "function/null/null_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

void VectorNullFunction::bindExecFunction(ExpressionType expressionType,
    const binder::expression_vector& /*children*/, scalar_func_exec_t& func) {
    switch (expressionType) {
    case ExpressionType::IS_NULL: {
        func = UnaryNullExecFunction<IsNull>;
        return;
    }
    case ExpressionType::IS_NOT_NULL: {
        func = UnaryNullExecFunction<IsNotNull>;
        return;
    }
    default:
        throw RuntimeException("Invalid expression type " +
                               ExpressionTypeUtil::toString(expressionType) +
                               "for VectorNullOperations::bindUnaryExecFunction.");
    }
}

void VectorNullFunction::bindSelectFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_select_t& func) {
    KU_ASSERT(children.size() == 1);
    (void)children;
    switch (expressionType) {
    case ExpressionType::IS_NULL: {
        func = UnaryNullSelectFunction<IsNull>;
        return;
    }
    case ExpressionType::IS_NOT_NULL: {
        func = UnaryNullSelectFunction<IsNotNull>;
        return;
    }
    default:
        throw RuntimeException("Invalid expression type " +
                               ExpressionTypeUtil::toString(expressionType) +
                               "for VectorNullOperations::bindUnarySelectFunction.");
    }
}

} // namespace function
} // namespace lbug
