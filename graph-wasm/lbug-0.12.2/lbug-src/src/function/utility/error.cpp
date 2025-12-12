#include "common/exception/runtime.h"
#include "function/scalar_function.h"
#include "function/utility/vector_utility_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct Error {
    static void operation(ku_string_t& input, int32_t& result) {
        result = 0;
        throw RuntimeException(input.getAsString());
    }
};

function_set ErrorFunction::getFunctionSet() {
    function_set functionSet;
    functionSet.push_back(
        std::make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::STRING},
            LogicalTypeID::INT32, ScalarFunction::UnaryExecFunction<ku_string_t, int32_t, Error>));
    // int32_t is just a dummy resultType for error(), since this function throws an exception
    // instead of returns any result
    return functionSet;
}

} // namespace function
} // namespace lbug
