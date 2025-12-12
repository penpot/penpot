#include "common/exception/binder.h"
#include "common/exception/runtime.h"
#include "expression_evaluator/lambda_evaluator.h"
#include "expression_evaluator/list_slice_info.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

namespace lbug {
namespace function {

using namespace lbug::common;

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    if (input.arguments[1]->expressionType != ExpressionType::LAMBDA) {
        throw BinderException(stringFormat(
            "The second argument of LIST_REDUCE should be a lambda expression but got {}.",
            ExpressionTypeUtil::toString(input.arguments[1]->expressionType)));
    }
    std::vector<LogicalType> paramTypes;
    paramTypes.push_back(input.arguments[0]->getDataType().copy());
    paramTypes.push_back(input.arguments[1]->getDataType().copy());
    return std::make_unique<FunctionBindData>(std::move(paramTypes),
        ListType::getChildType(input.arguments[0]->getDataType()).copy());
}

static void processDataEntry(offset_t curOffset, sel_t listEntryPos, common::ValueVector& result,
    const common::ValueVector& inputVector, common::ValueVector& tmpResultVector,
    const common::SelectionVector& tmpResultVectorSelVector,
    const std::vector<ValueVector*>& params, const std::vector<common::idx_t>& paramIndices,
    evaluator::ListLambdaBindData& bindData) {
    common::ValueVector& inputDataVector = *ListVector::getDataVector(&inputVector);
    const auto listEntry = inputVector.getValue<list_entry_t>(listEntryPos);
    KU_ASSERT(listEntry.size > 0);
    offset_t offsetInList = curOffset - listEntry.offset;
    if (offsetInList == 0 && listEntry.size == 1) {
        // if list size is 1 the reduce result is equal to the single value
        result.copyFromVectorData(listEntryPos, &inputDataVector, listEntry.offset);
    } else {
        auto paramPos = params[0]->state->getSelVector()[0];
        auto tmpResultPos = tmpResultVectorSelVector[0];
        if (offsetInList < listEntry.size - 1) {
            // continue reducing
            for (auto i = 0u; i < params.size(); i++) {
                if (0u == paramIndices[i] && 0u != offsetInList) {
                    params[i]->copyFromVectorData(paramPos, &tmpResultVector, tmpResultPos);
                } else {
                    params[i]->copyFromVectorData(paramPos, &inputDataVector,
                        listEntry.offset + offsetInList + paramIndices[i]);
                }
                params[i]->state->getSelVectorUnsafe().setSelSize(1);
            }
            bindData.rootEvaluator->evaluate();
        } else {
            // we are done reducing, copy the result from the intermediate result vector
            result.copyFromVectorData(listEntryPos, &tmpResultVector, tmpResultPos);
        }
    }
}

static void reduceSlice(evaluator::ListSliceInfo& sliceInfo, common::ValueVector& result,
    const common::ValueVector& inputVector, common::ValueVector& tmpResultVector,
    common::SelectionVector& tmpResultVectorSelVector, evaluator::ListLambdaBindData& bindData) {
    const auto& paramIndices = bindData.paramIndices;
    std::vector<ValueVector*> params(bindData.lambdaParamEvaluators.size());
    for (auto i = 0u; i < bindData.lambdaParamEvaluators.size(); i++) {
        auto param = bindData.lambdaParamEvaluators[i]->resultVector.get();
        params[i] = param;
    }

    for (sel_t i = 0; i < sliceInfo.getSliceSize(); ++i) {
        const auto [listEntryPos, dataOffset] = sliceInfo.getPos(i);
        processDataEntry(dataOffset, listEntryPos, result, inputVector, tmpResultVector,
            tmpResultVectorSelVector, params, paramIndices, bindData);
    }
}

static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>& input,
    const std::vector<common::SelectionVector*>& inputSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* bindData) {
    KU_ASSERT(input.size() == 2);
    auto listLambdaBindData = reinterpret_cast<evaluator::ListLambdaBindData*>(bindData);
    const auto* inputVector = input[0].get();
    reduceSlice(*listLambdaBindData->sliceInfo, result, *inputVector, *input[1].get(),
        *inputSelVectors[1], *listLambdaBindData);

    if (listLambdaBindData->sliceInfo->done()) {
        for (idx_t i = 0; i < inputSelVectors[0]->getSelSize(); ++i) {
            const auto pos = (*inputSelVectors[0])[i];
            const auto resPos = (*resultSelVector)[i];
            if (inputVector->isNull(pos)) {
                result.setNull(resPos, true);
            } else if (inputVector->getValue<list_entry_t>(pos).size == 0) {
                throw common::RuntimeException{"Cannot execute list_reduce on an empty list."};
            }
        }
    }
}

function_set ListReduceFunction::getFunctionSet() {
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
