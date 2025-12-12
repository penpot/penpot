#include "function/list/functions/list_extract_function.h"

#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

template<typename LEFT_TYPE, typename RIGHT_TYPE, typename RESULT_TYPE, typename FUNC>
static void BinaryExecListExtractFunction(
    const std::vector<std::shared_ptr<common::ValueVector>>& params,
    const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* dataPtr = nullptr) {
    KU_ASSERT(params.size() == 2);
    BinaryFunctionExecutor::executeSwitch<LEFT_TYPE, RIGHT_TYPE, RESULT_TYPE, FUNC,
        BinaryListExtractFunctionWrapper>(*params[0], paramSelVectors[0], *params[1],
        paramSelVectors[1], result, resultSelVector, dataPtr);
}

static std::unique_ptr<FunctionBindData> ListExtractBindFunc(const ScalarBindFuncInput& input) {
    const auto& resultType = ListType::getChildType(input.arguments[0]->dataType);
    auto scalarFunction = input.definition->ptrCast<ScalarFunction>();
    TypeUtils::visit(resultType.getPhysicalType(), [&scalarFunction]<typename T>(T) {
        scalarFunction->execFunc =
            BinaryExecListExtractFunction<list_entry_t, int64_t, T, ListExtract>;
    });
    std::vector<LogicalType> paramTypes;
    paramTypes.push_back(input.arguments[0]->getDataType().copy());
    paramTypes.push_back(LogicalType(input.definition->parameterTypeIDs[1]));
    return std::make_unique<FunctionBindData>(std::move(paramTypes), resultType.copy());
}

function_set ListExtractFunction::getFunctionSet() {
    function_set result;
    std::unique_ptr<ScalarFunction> func;
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::INT64}, LogicalTypeID::ANY);
    func->bindFunc = ListExtractBindFunc;
    result.push_back(std::move(func));
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::INT64},
        LogicalTypeID::STRING,
        ScalarFunction::BinaryExecFunction<ku_string_t, int64_t, ku_string_t, ListExtract>);
    result.push_back(std::move(func));
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ARRAY, LogicalTypeID::INT64}, LogicalTypeID::ANY);
    func->bindFunc = ListExtractBindFunc;
    result.push_back(std::move(func));
    return result;
}

} // namespace function
} // namespace lbug
