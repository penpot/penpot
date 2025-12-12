#include "function/scalar_function.h"
#include "function/union/functions/union_tag.h"
#include "function/union/vector_union_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    return FunctionBindData::getSimpleBindData(input.arguments, LogicalType::STRING());
}

function_set UnionTagFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::UNION}, LogicalTypeID::STRING,
        ScalarFunction::UnaryExecNestedTypeFunction<union_entry_t, ku_string_t, UnionTag>);
    function->bindFunc = bindFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
