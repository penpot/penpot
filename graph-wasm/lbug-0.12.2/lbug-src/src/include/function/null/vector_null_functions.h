#pragma once

#include "function/scalar_function.h"
#include "null_function_executor.h"

namespace lbug {
namespace function {

class VectorNullFunction {
public:
    static void bindExecFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_exec_t& func);

    static void bindSelectFunction(common::ExpressionType expressionType,
        const binder::expression_vector& children, scalar_func_select_t& func);

private:
    template<typename FUNC>
    static void UnaryNullExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector*, void* /*dataPtr*/ = nullptr) {
        KU_ASSERT(params.size() == 1);
        NullOperationExecutor::execute<FUNC>(*params[0], *paramSelVectors[0], result);
    }

    template<typename FUNC>
    static bool UnaryNullSelectFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        common::SelectionVector& selVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        return NullOperationExecutor::select<FUNC>(*params[0], selVector, dataPtr);
    }
};

} // namespace function
} // namespace lbug
