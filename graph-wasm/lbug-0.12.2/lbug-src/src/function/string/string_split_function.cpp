#include "common/string_utils.h"
#include "function/string/vector_string_functions.h"

namespace lbug {
namespace function {

using namespace lbug::common;

struct StringSplit {
    static void operation(ku_string_t& strToSplit, ku_string_t& separator, list_entry_t& result,
        ValueVector& resultVector) {
        auto splitStrVec = StringUtils::split(strToSplit.getAsString(), separator.getAsString());
        result = ListVector::addList(&resultVector, splitStrVec.size());
        for (auto i = 0u; i < result.size; i++) {
            ListVector::getDataVector(&resultVector)->setValue(result.offset + i, splitStrVec[i]);
        }
    }
};

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    return FunctionBindData::getSimpleBindData(input.arguments,
        LogicalType::LIST(LogicalType::STRING()));
}

function_set StringSplitFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::STRING},
        LogicalTypeID::LIST,
        ScalarFunction::BinaryStringExecFunction<ku_string_t, ku_string_t, list_entry_t,
            StringSplit>);
    function->bindFunc = bindFunc;
    functionSet.emplace_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
