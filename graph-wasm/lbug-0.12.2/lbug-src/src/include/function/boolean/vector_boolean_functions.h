#pragma once

#include "boolean_function_executor.h"
#include "function/scalar_function.h"

namespace lbug {
namespace function {

class VectorBooleanFunction {
public:
    static void bindExecFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_exec_t& func);

    static void bindSelectFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_select_t& func);

private:
    template<typename FUNC>
    static void BinaryBooleanExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* /*dataPtr*/ = nullptr) {
        KU_ASSERT(params.size() == 2);
        BinaryBooleanFunctionExecutor::execute<FUNC>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], result, resultSelVector);
    }

    template<typename FUNC>
    static bool BinaryBooleanSelectFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        common::SelectionVector& selVector, void* /*dataPtr*/) {
        KU_ASSERT(params.size() == 2);
        return BinaryBooleanFunctionExecutor::select<FUNC>(*params[0], *params[1], selVector);
    }

    template<typename FUNC>
    static void UnaryBooleanExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* /*dataPtr*/ = nullptr) {
        KU_ASSERT(params.size() == 1);
        UnaryBooleanOperationExecutor::execute<FUNC>(*params[0], paramSelVectors[0], result,
            resultSelVector);
    }

    template<typename FUNC>
    static bool UnaryBooleanSelectFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        common::SelectionVector& selVector, void* /*dataPtr*/) {
        KU_ASSERT(params.size() == 1);
        return UnaryBooleanOperationExecutor::select<FUNC>(*params[0], selVector);
    }

    static void bindBinaryExecFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_exec_t& func);

    static void bindBinarySelectFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_select_t& func);

    static void bindUnaryExecFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_exec_t& func);

    static void bindUnarySelectFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_select_t& func);
};

} // namespace function
} // namespace lbug
