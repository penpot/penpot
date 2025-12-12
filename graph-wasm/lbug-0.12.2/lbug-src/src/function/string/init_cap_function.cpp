#include "function/string/vector_string_functions.h"

namespace lbug {
namespace function {

using namespace lbug::common;

struct InitCap {
    static void operation(ku_string_t& operand, ku_string_t& result, ValueVector& resultVector) {
        Lower::operation(operand, result, resultVector);
        int originalSize = 0, newSize = 0;
        BaseLowerUpperFunction::convertCharCase(reinterpret_cast<char*>(result.getDataUnsafe()),
            reinterpret_cast<const char*>(result.getData()), 0 /* charPos */, true /* toUpper */,
            originalSize, newSize);
    }
};

function_set InitCapFunction::getFunctionSet() {
    function_set functionSet;
    functionSet.emplace_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING}, LogicalTypeID::STRING,
        ScalarFunction::UnaryStringExecFunction<ku_string_t, ku_string_t, InitCap>));
    return functionSet;
}

} // namespace function
} // namespace lbug
