#include "common/exception/binder.h"
#include "expression_evaluator/lambda_evaluator.h"
#include "expression_evaluator/list_slice_info.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

namespace lbug {
namespace function {

using namespace common;

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    if (input.arguments[1]->expressionType != ExpressionType::LAMBDA) {
        throw BinderException(stringFormat(
            "The second argument of LIST_TRANSFORM should be a lambda expression but got {}.",
            ExpressionTypeUtil::toString(input.arguments[1]->expressionType)));
    }
    std::vector<LogicalType> paramTypes;
    paramTypes.push_back(input.arguments[0]->getDataType().copy());
    paramTypes.push_back(input.arguments[1]->getDataType().copy());
    return std::make_unique<FunctionBindData>(std::move(paramTypes),
        LogicalType::LIST(input.arguments[1]->getDataType().copy()));
}

static void copyEvaluatedDataToResult(ValueVector& resultVector,
    evaluator::ListLambdaBindData* bindData) {
    auto& sliceInfo = *bindData->sliceInfo;
    auto dstDataVector = ListVector::getDataVector(&resultVector);
    auto rootResultVector = bindData->rootEvaluator->resultVector.get();
    for (sel_t i = 0; i < sliceInfo.getSliceSize(); ++i) {
        const auto [listEntryPos, dataOffset] = sliceInfo.getPos(i);
        const auto srcIdx = bindData->lambdaParamEvaluators.empty() ? 0 : i;
        sel_t srcPos = rootResultVector->state->getSelVector()[srcIdx];
        dstDataVector->copyFromVectorData(dataOffset, rootResultVector, srcPos);
        dstDataVector->setNull(dataOffset, rootResultVector->isNull(srcPos));
    }
}

static void copyListEntriesToResult(const ValueVector& inputVector,
    const SelectionVector& inputSelVector, ValueVector& result) {
    for (uint64_t i = 0; i < inputSelVector.getSelSize(); ++i) {
        auto pos = inputSelVector[i];
        result.setNull(pos, inputVector.isNull(pos));

        auto inputList = inputVector.getValue<list_entry_t>(pos);
        ListVector::addList(&result, inputList.size);
        result.setValue(pos, inputList);
    }
}

static void execFunc(const std::vector<std::shared_ptr<ValueVector>>& input,
    const std::vector<SelectionVector*>& inputSelVectors, ValueVector& result,
    SelectionVector* resultSelVector, void* bindData_) {
    auto bindData = reinterpret_cast<evaluator::ListLambdaBindData*>(bindData_);
    auto* sliceInfo = bindData->sliceInfo;
    auto savedParamStates = sliceInfo->overrideAndSaveParamStates(bindData->lambdaParamEvaluators);

    bindData->rootEvaluator->evaluate();
    copyEvaluatedDataToResult(result, bindData);

    auto& inputVector = *input[0];
    const auto& inputSelVector = *inputSelVectors[0];
    KU_ASSERT(input.size() == 2);
    if (!bindData->lambdaParamEvaluators.empty()) {
        if (sliceInfo->done()) {
            ListVector::copyListEntryAndBufferMetaData(result, *resultSelVector, inputVector,
                inputSelVector);
        }
    } else {
        if (sliceInfo->done()) {
            copyListEntriesToResult(inputVector, inputSelVector, result);
        }
    }

    sliceInfo->restoreParamStates(bindData->lambdaParamEvaluators, std::move(savedParamStates));
}

function_set ListTransformFunction::getFunctionSet() {
    function_set result;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::ANY}, LogicalTypeID::LIST,
        execFunc);
    function->bindFunc = bindFunc;
    function->isListLambda = true;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
