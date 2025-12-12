#include "function/list/functions/list_len_function.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> sizeBindFunc(const ScalarBindFuncInput& input) {
    auto scalarFunc = input.definition->constPtrCast<ScalarFunction>();
    auto resultType = LogicalType(scalarFunc->returnTypeID);
    if (input.definition->parameterTypeIDs[0] == common::LogicalTypeID::STRING) {
        std::vector<LogicalType> paramTypes;
        paramTypes.push_back(LogicalType::STRING());
        return std::make_unique<FunctionBindData>(std::move(paramTypes), resultType.copy());
    } else {
        return FunctionBindData::getSimpleBindData(input.arguments, resultType);
    }
}

function_set SizeFunction::getFunctionSet() {
    function_set result;
    // size(list)
    auto listFunc = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST}, LogicalTypeID::INT64,
        ScalarFunction::UnaryExecFunction<list_entry_t, int64_t, ListLen>);
    listFunc->bindFunc = sizeBindFunc;
    result.push_back(std::move(listFunc));
    // size(array)
    auto arrayFunc = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{
            LogicalTypeID::ARRAY,
        },
        LogicalTypeID::INT64, ScalarFunction::UnaryExecFunction<list_entry_t, int64_t, ListLen>);
    arrayFunc->bindFunc = sizeBindFunc;
    result.push_back(std::move(arrayFunc));
    // size(map)
    auto mapFunc = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::MAP}, LogicalTypeID::INT64,
        ScalarFunction::UnaryExecFunction<list_entry_t, int64_t, ListLen>);
    mapFunc->bindFunc = sizeBindFunc;
    result.push_back(std::move(mapFunc));
    // size(string)
    auto strFunc =
        std::make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::STRING},
            LogicalTypeID::INT64, ScalarFunction::UnaryExecFunction<ku_string_t, int64_t, ListLen>);
    strFunc->bindFunc = sizeBindFunc;
    result.push_back(std::move(strFunc));
    return result;
}

} // namespace function
} // namespace lbug
