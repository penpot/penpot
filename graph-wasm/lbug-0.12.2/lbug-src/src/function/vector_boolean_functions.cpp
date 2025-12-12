#include "function/boolean/vector_boolean_functions.h"

#include "common/exception/runtime.h"
#include "function/boolean/boolean_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

void VectorBooleanFunction::bindExecFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_exec_t& func) {
    if (ExpressionTypeUtil::isBinary(expressionType)) {
        bindBinaryExecFunction(expressionType, children, func);
    } else {
        KU_ASSERT(ExpressionTypeUtil::isUnary(expressionType));
        bindUnaryExecFunction(expressionType, children, func);
    }
}

void VectorBooleanFunction::bindSelectFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_select_t& func) {
    if (ExpressionTypeUtil::isBinary(expressionType)) {
        bindBinarySelectFunction(expressionType, children, func);
    } else {
        KU_ASSERT(ExpressionTypeUtil::isUnary(expressionType));
        bindUnarySelectFunction(expressionType, children, func);
    }
}

void VectorBooleanFunction::bindBinaryExecFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_exec_t& func) {
    KU_ASSERT(children.size() == 2);
    const auto& leftType = children[0]->dataType;
    const auto& rightType = children[1]->dataType;
    (void)leftType;
    (void)rightType;
    KU_ASSERT(leftType.getLogicalTypeID() == LogicalTypeID::BOOL &&
              rightType.getLogicalTypeID() == LogicalTypeID::BOOL);
    switch (expressionType) {
    case ExpressionType::AND: {
        func = &BinaryBooleanExecFunction<And>;
        return;
    }
    case ExpressionType::OR: {
        func = &BinaryBooleanExecFunction<Or>;
        return;
    }
    case ExpressionType::XOR: {
        func = &BinaryBooleanExecFunction<Xor>;
        return;
    }
    default:
        throw RuntimeException("Invalid expression type " +
                               ExpressionTypeUtil::toString(expressionType) +
                               " for VectorBooleanFunctions::bindBinaryExecFunction.");
    }
}

void VectorBooleanFunction::bindBinarySelectFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_select_t& func) {
    KU_ASSERT(children.size() == 2);
    const auto& leftType = children[0]->dataType;
    const auto& rightType = children[1]->dataType;
    (void)leftType;
    (void)rightType;
    KU_ASSERT(leftType.getLogicalTypeID() == LogicalTypeID::BOOL &&
              rightType.getLogicalTypeID() == LogicalTypeID::BOOL);
    switch (expressionType) {
    case ExpressionType::AND: {
        func = &BinaryBooleanSelectFunction<And>;
        return;
    }
    case ExpressionType::OR: {
        func = &BinaryBooleanSelectFunction<Or>;
        return;
    }
    case ExpressionType::XOR: {
        func = &BinaryBooleanSelectFunction<Xor>;
        return;
    }
    default:
        throw RuntimeException("Invalid expression type " +
                               ExpressionTypeUtil::toString(expressionType) +
                               " for VectorBooleanFunctions::bindBinarySelectFunction.");
    }
}

void VectorBooleanFunction::bindUnaryExecFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_exec_t& func) {
    KU_ASSERT(
        children.size() == 1 && children[0]->dataType.getLogicalTypeID() == LogicalTypeID::BOOL);
    (void)children;
    switch (expressionType) {
    case ExpressionType::NOT: {
        func = &UnaryBooleanExecFunction<Not>;
        return;
    }
    default:
        throw RuntimeException("Invalid expression type " +
                               ExpressionTypeUtil::toString(expressionType) +
                               " for VectorBooleanFunctions::bindUnaryExecFunction.");
    }
}

void VectorBooleanFunction::bindUnarySelectFunction(ExpressionType expressionType,
    const binder::expression_vector& children, scalar_func_select_t& func) {
    KU_ASSERT(
        children.size() == 1 && children[0]->dataType.getLogicalTypeID() == LogicalTypeID::BOOL);
    (void)children;
    switch (expressionType) {
    case ExpressionType::NOT: {
        func = &UnaryBooleanSelectFunction<Not>;
        return;
    }
    default:
        throw RuntimeException("Invalid expression type " +
                               ExpressionTypeUtil::toString(expressionType) +
                               " for VectorBooleanFunctions::bindUnaryExecFunction.");
    }
}

} // namespace function
} // namespace lbug
