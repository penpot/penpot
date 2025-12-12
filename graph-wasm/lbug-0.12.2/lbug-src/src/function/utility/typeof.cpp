#include "function/scalar_function.h"
#include "function/utility/function_string_bind_data.h"
#include "function/utility/vector_utility_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    std::unique_ptr<FunctionBindData> bindData;
    if (input.arguments[0]->getDataType().getLogicalTypeID() == LogicalTypeID::ANY) {
        bindData = std::make_unique<FunctionStringBindData>("NULL");
        bindData->paramTypes.push_back(LogicalType::STRING());
    } else {
        bindData =
            std::make_unique<FunctionStringBindData>(input.arguments[0]->getDataType().toString());
    }
    return bindData;
}

static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>&,
    const std::vector<common::SelectionVector*>&, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* dataPtr) {
    result.resetAuxiliaryBuffer();
    auto typeData = reinterpret_cast<FunctionStringBindData*>(dataPtr);
    for (auto i = 0u; i < resultSelVector->getSelSize(); ++i) {
        auto resultPos = (*resultSelVector)[i];
        StringVector::addString(&result, resultPos, typeData->str);
    }
}

function_set TypeOfFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ANY}, LogicalTypeID::STRING, execFunc);
    function->bindFunc = bindFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
