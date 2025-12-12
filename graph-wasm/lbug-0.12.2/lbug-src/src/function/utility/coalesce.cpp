#include "binder/expression/expression_util.h"
#include "common/exception/binder.h"
#include "function/scalar_function.h"
#include "function/utility/vector_utility_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    if (input.arguments.empty()) {
        throw BinderException("COALESCE requires at least one argument");
    }
    LogicalType resultType(LogicalTypeID::ANY);
    binder::ExpressionUtil::tryCombineDataType(input.arguments, resultType);
    if (resultType.getLogicalTypeID() == LogicalTypeID::ANY) {
        resultType = LogicalType::STRING();
    }
    auto bindData = std::make_unique<FunctionBindData>(resultType.copy());
    for (auto& _ : input.arguments) {
        (void)_;
        bindData->paramTypes.push_back(resultType.copy());
    }
    return bindData;
}

static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>& params,
    const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* /*dataPtr*/) {
    result.resetAuxiliaryBuffer();
    for (auto i = 0u; i < resultSelVector->getSelSize(); ++i) {
        auto resultPos = (*resultSelVector)[i];
        auto isNull = true;
        for (size_t i = 0; i < params.size(); ++i) {
            const auto& param = *params[i];
            const auto& paramSelVector = *paramSelVectors[i];
            auto paramPos = param.state->isFlat() ? paramSelVector[0] : resultPos;
            if (!param.isNull(paramPos)) {
                result.copyFromVectorData(resultPos, &param, paramPos);
                isNull = false;
                break;
            }
        }
        result.setNull(resultPos, isNull);
    }
}

static bool selectFunc(const std::vector<std::shared_ptr<ValueVector>>& params,
    SelectionVector& selVector, void* /* dataPtr */) {
    KU_ASSERT(!params.empty());
    auto unFlatVectorIdx = 0u;
    for (auto i = 0u; i < params.size(); ++i) {
        if (!params[i]->state->isFlat()) {
            unFlatVectorIdx = i;
            break;
        }
    }
    auto numSelectedValues = 0u;
    auto selectedPositionsBuffer = selVector.getMutableBuffer();
    for (auto i = 0u; i < params[unFlatVectorIdx]->state->getSelVector().getSelSize(); ++i) {
        auto resultPos = params[unFlatVectorIdx]->state->getSelVector()[i];
        auto resultValue = false;
        for (auto& param : params) {
            auto paramPos = param->state->isFlat() ? param->state->getSelVector()[0] : resultPos;
            if (!param->isNull(paramPos)) {
                resultValue = param->getValue<bool>(paramPos);
                break;
            }
        }
        selectedPositionsBuffer[numSelectedValues] = resultPos;
        numSelectedValues += resultValue;
    }
    selVector.setSelSize(numSelectedValues);
    return numSelectedValues > 0;
}

function_set CoalesceFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ANY}, LogicalTypeID::ANY, execFunc, selectFunc);
    function->bindFunc = bindFunc;
    function->isVarLength = true;
    functionSet.push_back(std::move(function));
    return functionSet;
}

function_set IfNullFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ANY, LogicalTypeID::ANY}, LogicalTypeID::ANY,
        execFunc, selectFunc);
    function->bindFunc = bindFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
