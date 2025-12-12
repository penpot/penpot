#include "common/type_utils.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct ListToString {
    static void operation(ku_string_t& delim, list_entry_t& input, common::ku_string_t& result,
        common::ValueVector& inputVector, common::ValueVector& /*delimVector*/,
        common::ValueVector& resultVector);
};

void ListToString::operation(ku_string_t& delim, list_entry_t& input, ku_string_t& result,
    ValueVector& /*delimVector*/, ValueVector& inputVector, ValueVector& resultVector) {
    std::string resultStr = "";
    bool outputDelim = false;
    if (input.size != 0) {
        auto dataVector = ListVector::getDataVector(&inputVector);
        if (!dataVector->isNull(input.offset)) {
            resultStr += TypeUtils::entryToString(dataVector->dataType,
                ListVector::getListValuesWithOffset(&inputVector, input, 0 /* offset */),
                dataVector);
            outputDelim = true;
        }
        for (auto i = 1u; i < input.size; i++) {
            if (dataVector->isNull(input.offset + i)) {
                continue;
            }
            if (outputDelim) {
                resultStr += delim.getAsString();
            }
            outputDelim = true;
            resultStr += TypeUtils::entryToString(dataVector->dataType,
                ListVector::getListValuesWithOffset(&inputVector, input, i), dataVector);
        }
    }
    StringVector::addString(&resultVector, result, resultStr);
}

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    std::vector<LogicalType> paramTypes;
    paramTypes.push_back(LogicalType(input.definition->parameterTypeIDs[0]));
    if (input.arguments[1]->getDataType().getLogicalTypeID() == LogicalTypeID::ANY) {
        paramTypes.push_back(LogicalType::STRING());
    } else {
        paramTypes.push_back(input.arguments[1]->getDataType().copy());
    }
    return std::make_unique<FunctionBindData>(std::move(paramTypes), LogicalType::STRING());
}

function_set ListToStringFunction::getFunctionSet() {
    function_set result;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::LIST},
        LogicalTypeID::STRING,
        ScalarFunction::BinaryExecListStructFunction<ku_string_t, list_entry_t, ku_string_t,
            ListToString>);
    function->bindFunc = bindFunc;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
