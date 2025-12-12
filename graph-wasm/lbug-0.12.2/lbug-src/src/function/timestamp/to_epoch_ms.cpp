#include "function/arithmetic/divide.h"
#include "function/scalar_function.h"
#include "function/timestamp/vector_timestamp_functions.h"

namespace lbug {
namespace function {

using namespace lbug::common;

struct ToEpochMs {
    static void operation(common::timestamp_t& input, int64_t& result) {
        function::Divide::operation(input.value, Interval::MICROS_PER_MSEC, result);
    }
};

function_set ToEpochMsFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::TIMESTAMP}, LogicalTypeID::INT64,
        ScalarFunction::UnaryExecFunction<timestamp_t, int64_t, ToEpochMs>);
    functionSet.emplace_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
