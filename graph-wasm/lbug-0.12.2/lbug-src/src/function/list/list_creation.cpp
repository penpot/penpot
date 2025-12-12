#include "binder/expression/expression_util.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

void ListCreationFunction::execFunc(
    const std::vector<std::shared_ptr<common::ValueVector>>& parameters,
    const std::vector<common::SelectionVector*>& parameterSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* /*dataPtr*/) {
    result.resetAuxiliaryBuffer();
    for (auto selectedPos = 0u; selectedPos < resultSelVector->getSelSize(); ++selectedPos) {
        auto pos = (*resultSelVector)[selectedPos];
        auto resultEntry = ListVector::addList(&result, parameters.size());
        result.setValue(pos, resultEntry);
        auto resultDataVector = ListVector::getDataVector(&result);
        auto resultPos = resultEntry.offset;
        for (auto i = 0u; i < parameters.size(); i++) {
            const auto& parameter = parameters[i];
            const auto& parameterSelVector = *parameterSelVectors[i];
            auto paramPos = parameter->state->isFlat() ? parameterSelVector[0] : pos;
            resultDataVector->copyFromVectorData(resultPos++, parameter.get(), paramPos);
        }
    }
}

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    LogicalType combinedType(LogicalTypeID::ANY);
    binder::ExpressionUtil::tryCombineDataType(input.arguments, combinedType);
    if (combinedType.getLogicalTypeID() == LogicalTypeID::ANY) {
        combinedType = LogicalType::INT64();
    }
    auto resultType = LogicalType::LIST(combinedType.copy());
    auto bindData = std::make_unique<FunctionBindData>(std::move(resultType));
    for (auto& _ : input.arguments) {
        (void)_;
        bindData->paramTypes.push_back(combinedType.copy());
    }
    return bindData;
}

function_set ListCreationFunction::getFunctionSet() {
    function_set result;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ANY}, LogicalTypeID::LIST, execFunc);
    function->bindFunc = bindFunc;
    function->isVarLength = true;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
