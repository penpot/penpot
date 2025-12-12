#pragma once

#include "binary_function_executor.h"
#include "const_function_executor.h"
#include "function.h"
#include "pointer_function_executor.h"
#include "ternary_function_executor.h"
#include "unary_function_executor.h"

namespace lbug {
namespace function {

// Evaluate function at compile time, e.g. struct_extraction.
using scalar_func_compile_exec_t =
    std::function<void(FunctionBindData*, const std::vector<std::shared_ptr<common::ValueVector>>&,
        std::shared_ptr<common::ValueVector>&)>;
// Execute function.
using scalar_func_exec_t =
    std::function<void(const std::vector<std::shared_ptr<common::ValueVector>>&,
        const std::vector<common::SelectionVector*>&, common::ValueVector&,
        common::SelectionVector*, void*)>;
// Execute boolean function and write result to selection vector. Fast path for filter.
using scalar_func_select_t = std::function<bool(
    const std::vector<std::shared_ptr<common::ValueVector>>&, common::SelectionVector&, void*)>;

struct LBUG_API ScalarFunction : public ScalarOrAggregateFunction {
    scalar_func_exec_t execFunc = nullptr;
    scalar_func_select_t selectFunc = nullptr;
    scalar_func_compile_exec_t compileFunc = nullptr;
    bool isListLambda = false;
    bool isVarLength = false;

    ScalarFunction() = default;
    ScalarFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs,
        common::LogicalTypeID returnTypeID)
        : ScalarOrAggregateFunction{std::move(name), std::move(parameterTypeIDs), returnTypeID} {}
    ScalarFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs,
        common::LogicalTypeID returnTypeID, scalar_func_exec_t execFunc)
        : ScalarOrAggregateFunction{std::move(name), std::move(parameterTypeIDs), returnTypeID},
          execFunc{std::move(execFunc)} {}
    ScalarFunction(std::string name, std::vector<common::LogicalTypeID> parameterTypeIDs,
        common::LogicalTypeID returnTypeID, scalar_func_exec_t execFunc,
        scalar_func_select_t selectFunc)
        : ScalarOrAggregateFunction{std::move(name), std::move(parameterTypeIDs), returnTypeID},
          execFunc{std::move(execFunc)}, selectFunc{std::move(selectFunc)} {}

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC>
    static void TernaryExecFunction(const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 3);
        TernaryFunctionExecutor::executeSwitch<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC,
            TernaryFunctionWrapper>(*params[0], paramSelVectors[0], *params[1], paramSelVectors[1],
            *params[2], paramSelVectors[2], result, resultSelVector, dataPtr);
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC>
    static void TernaryStringExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 3);
        TernaryFunctionExecutor::executeSwitch<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC,
            TernaryStringFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], *params[2], paramSelVectors[2], result, resultSelVector, dataPtr);
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC>
    static void TernaryRegexExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        TernaryFunctionExecutor::executeSwitch<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC,
            TernaryRegexFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], *params[2], paramSelVectors[2], result, resultSelVector, dataPtr);
    }

    template<typename A_TYPE, typename B_TYPE, typename C_TYPE, typename RESULT_TYPE, typename FUNC>
    static void TernaryExecListStructFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 3);
        TernaryFunctionExecutor::executeSwitch<A_TYPE, B_TYPE, C_TYPE, RESULT_TYPE, FUNC,
            TernaryListFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], *params[2], paramSelVectors[2], result, resultSelVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
    static void BinaryExecFunction(const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* /*dataPtr*/ = nullptr) {
        KU_ASSERT(params.size() == 2);
        BinaryFunctionExecutor::execute<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC>(*params[0],
            paramSelVectors[0], *params[1], paramSelVectors[1], result, resultSelVector);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
    static void BinaryStringExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 2);
        BinaryFunctionExecutor::executeSwitch<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC,
            BinaryStringFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], result, resultSelVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
    static void BinaryExecListStructFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
        KU_ASSERT(params.size() == 2);
        BinaryFunctionExecutor::executeSwitch<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC,
            BinaryListStructFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], result, resultSelVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
    static void BinaryExecWithBindData(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 2);
        BinaryFunctionExecutor::executeSwitch<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC,
            BinaryMapCreationFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
            paramSelVectors[1], result, resultSelVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename FUNC>
    static bool BinarySelectFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        common::SelectionVector& selVector, void* dataPtr) {
        KU_ASSERT(params.size() == 2);
        return BinaryFunctionExecutor::select<LEFT_TYPE, RIGHT_TYPE, FUNC>(*params[0], *params[1],
            selVector, dataPtr);
    }

    template<typename LEFT_TYPE, typename RIGHT_TYPE, typename FUNC>
    static bool BinarySelectWithBindData(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        common::SelectionVector& selVector, void* dataPtr) {
        KU_ASSERT(params.size() == 2);
        return BinaryFunctionExecutor::select<LEFT_TYPE, RIGHT_TYPE, FUNC,
            BinarySelectWithBindDataWrapper>(*params[0], *params[1], selVector, dataPtr);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC,
        typename EXECUTOR = UnaryFunctionExecutor>
    static void UnaryExecFunction(const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        EXECUTOR::template executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC, UnaryFunctionWrapper>(
            *params[0], paramSelVectors[0], result, resultSelVector, dataPtr);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void UnarySequenceExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        UnaryFunctionExecutor::executeSequence<OPERAND_TYPE, RESULT_TYPE, FUNC>(*params[0],
            paramSelVectors[0], result, resultSelVector, dataPtr);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC>
    static void UnaryStringExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* /*dataPtr*/ = nullptr) {
        KU_ASSERT(params.size() == 1);
        UnaryFunctionExecutor::executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC,
            UnaryStringFunctionWrapper>(*params[0], paramSelVectors[0], result, resultSelVector,
            nullptr /* dataPtr */);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC,
        typename EXECUTOR = UnaryFunctionExecutor>
    static void UnaryCastStringExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        EXECUTOR::template executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC,
            UnaryCastStringFunctionWrapper>(*params[0], paramSelVectors[0], result, resultSelVector,
            dataPtr);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC,
        typename EXECUTOR = UnaryFunctionExecutor, typename WRAPPER = UnaryCastFunctionWrapper>
    static void UnaryCastExecFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        EXECUTOR::template executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC, WRAPPER>(*params[0],
            paramSelVectors[0], result, resultSelVector, dataPtr);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC,
        typename EXECUTOR = UnaryFunctionExecutor>
    static void UnaryExecNestedTypeFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        EXECUTOR::template executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC,
            UnaryNestedTypeFunctionWrapper>(*params[0], paramSelVectors[0], result, resultSelVector,
            dataPtr);
    }

    template<typename OPERAND_TYPE, typename RESULT_TYPE, typename FUNC,
        typename EXECUTOR = UnaryFunctionExecutor>
    static void UnarySetSeedFunction(
        const std::vector<std::shared_ptr<common::ValueVector>>& params,
        const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
        common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.size() == 1);
        EXECUTOR::template executeSwitch<OPERAND_TYPE, RESULT_TYPE, FUNC, SetSeedFunctionWrapper>(
            *params[0], paramSelVectors[0], result, resultSelVector, dataPtr);
    }

    template<typename RESULT_TYPE, typename FUNC>
    static void NullaryExecFunction(
        [[maybe_unused]] const std::vector<std::shared_ptr<common::ValueVector>>& params,
        [[maybe_unused]] const std::vector<common::SelectionVector*>& paramSelVectors,
        common::ValueVector& result, common::SelectionVector* resultSelVector,
        void* /*dataPtr*/ = nullptr) {
        KU_ASSERT(params.empty() && paramSelVectors.empty());
        ConstFunctionExecutor::execute<RESULT_TYPE, FUNC>(result, *resultSelVector);
    }

    template<typename RESULT_TYPE, typename FUNC>
    static void NullaryAuxilaryExecFunction(
        [[maybe_unused]] const std::vector<std::shared_ptr<common::ValueVector>>& params,
        [[maybe_unused]] const std::vector<common::SelectionVector*>& paramSelVectors,
        common::ValueVector& result, common::SelectionVector* resultSelVector, void* dataPtr) {
        KU_ASSERT(params.empty() && paramSelVectors.empty());
        PointerFunctionExecutor::execute<RESULT_TYPE, FUNC>(result, *resultSelVector, dataPtr);
    }

    virtual std::unique_ptr<ScalarFunction> copy() const {
        return std::make_unique<ScalarFunction>(*this);
    }
};

} // namespace function
} // namespace lbug
