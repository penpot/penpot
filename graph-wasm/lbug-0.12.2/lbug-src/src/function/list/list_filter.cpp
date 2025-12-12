#include "common/exception/binder.h"
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
            "The second argument of LIST_FILTER should be a lambda expression but got {}.",
            ExpressionTypeUtil::toString(input.arguments[1]->expressionType)));
    }
    std::vector<LogicalType> paramTypes;
    paramTypes.push_back(input.arguments[0]->getDataType().copy());
    paramTypes.push_back(input.arguments[1]->getDataType().copy());
    if (input.arguments[1]->getDataType() != LogicalType::BOOL()) {
        throw BinderException(stringFormat(
            "{} requires the result type of lambda expression be BOOL.", ListFilterFunction::name));
    }
    return std::make_unique<FunctionBindData>(std::move(paramTypes),
        LogicalType::LIST(ListType::getChildType(input.arguments[0]->getDataType()).copy()));
}

static void constEvaluateFilterResult(const common::ValueVector& inputVector,
    const common::SelectionVector& listInputSelVector, common::ValueVector& result,
    common::SelectionVector& resultSelVector, const common::ValueVector& filterVector,
    const common::SelectionVector& filterSelVector, evaluator::ListSliceInfo* sliceInfo) {
    auto srcDataVector = ListVector::getDataVector(&inputVector);
    auto dstDataVector = ListVector::getDataVector(&result);
    KU_ASSERT(!filterVector.isNull(filterSelVector[0]));
    auto filterResult = filterVector.getValue<bool>(filterSelVector[0]);

    // resolve data vector
    if (filterResult) {
        for (sel_t i = 0; i < sliceInfo->getSliceSize(); ++i) {
            const auto [_, dataOffset] = sliceInfo->getPos(i);
            dstDataVector->copyFromVectorData(dataOffset, srcDataVector, dataOffset);
            dstDataVector->setNull(dataOffset, srcDataVector->isNull(dataOffset));
        }
    }

    // resolve list entries
    if (sliceInfo->done()) {
        for (uint64_t i = 0; i < listInputSelVector.getSelSize(); ++i) {
            list_entry_t dstListEntry;
            auto srcListEntry = inputVector.getValue<list_entry_t>(listInputSelVector[i]);
            if (filterResult) {
                dstListEntry = srcListEntry;
            } else {
                dstListEntry = {srcListEntry.offset, 0};
            }
            result.setValue(resultSelVector[i], dstListEntry);
        }
    }
}

static void evaluateFilterResult(const common::ValueVector& inputVector,
    common::ValueVector& result, const common::ValueVector& filterVector,
    [[maybe_unused]] const common::SelectionVector& filterSelVector,
    evaluator::ListSliceInfo* sliceInfo) {
    KU_ASSERT(filterSelVector.isUnfiltered());
    auto srcDataVector = ListVector::getDataVector(&inputVector);
    auto dstDataVector = ListVector::getDataVector(&result);

    auto& resultDataOffset = sliceInfo->getResultSliceOffset();
    for (sel_t i = 0; i < sliceInfo->getSliceSize(); ++i) {
        const auto [listEntryPos, dataOffset] = sliceInfo->getPos(i);
        const auto listEntry = inputVector.getValue<list_entry_t>(listEntryPos);
        if (dataOffset == listEntry.offset) {
            result.setValue(listEntryPos, list_entry_t{resultDataOffset, 0});
        }
        if (filterVector.getValue<bool>(i) && !filterVector.isNull(i)) {
            // TODO(Royi) make the output pos respect resultSelVector
            auto& resultListEntry = result.getValue<list_entry_t>(listEntryPos);
            dstDataVector->copyFromVectorData(resultDataOffset, srcDataVector, dataOffset);
            dstDataVector->setNull(resultDataOffset, srcDataVector->isNull(dataOffset));
            ++resultListEntry.size;
            ++resultDataOffset;
        }
    }
}

static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>& input,
    const std::vector<common::SelectionVector*>& inputSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* bindData) {
    auto listLambdaBindData = reinterpret_cast<evaluator::ListLambdaBindData*>(bindData);
    auto* sliceInfo = listLambdaBindData->sliceInfo;
    const auto& inputVector = *input[0];

    auto savedParamStates =
        sliceInfo->overrideAndSaveParamStates(listLambdaBindData->lambdaParamEvaluators);

    listLambdaBindData->rootEvaluator->evaluate();
    KU_ASSERT(input.size() == 2);
    auto& listInputSelVector = *inputSelVectors[0];
    auto& filterVector = *input[1];
    auto& filterSelVector = *inputSelVectors[1];

    if (listLambdaBindData->lambdaParamEvaluators.empty()) {
        constEvaluateFilterResult(inputVector, listInputSelVector, result, *resultSelVector,
            filterVector, filterSelVector, sliceInfo);
    } else {
        evaluateFilterResult(inputVector, result, filterVector, filterSelVector, sliceInfo);
    }

    if (listLambdaBindData->sliceInfo->done()) {
        for (idx_t i = 0; i < inputSelVectors[0]->getSelSize(); ++i) {
            const auto pos = (*inputSelVectors[0])[i];
            result.setNull(pos, inputVector.isNull(i));
        }
    }

    sliceInfo->restoreParamStates(listLambdaBindData->lambdaParamEvaluators,
        std::move(savedParamStates));
}

function_set ListFilterFunction::getFunctionSet() {
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
