#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

bool singleHandler(uint64_t numSelectedValues, uint64_t /*originalSize*/) {
    return numSelectedValues == 1;
}

function_set ListSingleFunction::getFunctionSet() {
    function_set result;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::ANY}, LogicalTypeID::BOOL,
        std::bind(execQuantifierFunc, singleHandler, std::placeholders::_1, std::placeholders::_2,
            std::placeholders::_3, std::placeholders::_4, std::placeholders::_5));
    function->bindFunc = bindQuantifierFunc;
    function->isListLambda = true;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
