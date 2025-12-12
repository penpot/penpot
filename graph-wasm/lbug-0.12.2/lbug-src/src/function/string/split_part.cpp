#include "common/string_utils.h"
#include "function/string/vector_string_functions.h"

namespace lbug {
namespace function {

using namespace lbug::common;

struct SplitPart {
    static void operation(ku_string_t& strToSplit, ku_string_t& separator, int64_t idx,
        ku_string_t& result, ValueVector& resultVector) {
        auto splitStrVec = StringUtils::split(strToSplit.getAsString(), separator.getAsString());
        bool idxOutOfRange = idx <= 0 || (uint64_t)idx > splitStrVec.size();
        std::string resultStr = idxOutOfRange ? "" : splitStrVec[idx - 1];
        StringVector::addString(&resultVector, result, resultStr);
    }
};

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    return FunctionBindData::getSimpleBindData(input.arguments, LogicalType::STRING());
}

function_set SplitPartFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::STRING,
            LogicalTypeID::INT64},
        LogicalTypeID::STRING,
        ScalarFunction::TernaryStringExecFunction<ku_string_t, ku_string_t, int64_t, ku_string_t,
            SplitPart>);
    function->bindFunc = bindFunc;
    functionSet.emplace_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
