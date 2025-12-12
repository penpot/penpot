#include "function/scalar_function.h"
#include "function/struct/vector_struct_functions.h"
#include "function/union/vector_union_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

function_set UnionExtractFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::UNION, LogicalTypeID::STRING},
        LogicalTypeID::ANY);
    function->bindFunc = StructExtractFunctions::bindFunc;
    function->compileFunc = StructExtractFunctions::compileFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
