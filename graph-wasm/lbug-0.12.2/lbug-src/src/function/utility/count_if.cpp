#include "common/type_utils.h"
#include "function/scalar_function.h"
#include "function/utility/vector_utility_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct CountIf {
    template<class T>
    static inline void operation(T& input, uint8_t& result) {
        if (input != 0) {
            result = 1;
        } else {
            result = 0;
        }
    }
};

function_set CountIfFunction::getFunctionSet() {
    function_set functionSet;
    auto operandTypeIDs = LogicalTypeUtils::getNumericalLogicalTypeIDs();
    operandTypeIDs.push_back(LogicalTypeID::BOOL);
    scalar_func_exec_t execFunc;
    for (auto operandTypeID : operandTypeIDs) {
        TypeUtils::visit(
            LogicalType(operandTypeID),
            [&execFunc]<NumericTypes T>(
                T) { execFunc = ScalarFunction::UnaryExecFunction<T, uint8_t, CountIf>; },
            [&execFunc](
                bool) { execFunc = ScalarFunction::UnaryExecFunction<bool, uint8_t, CountIf>; },
            [](auto) { KU_UNREACHABLE; });
        functionSet.push_back(std::make_unique<ScalarFunction>(name,
            std::vector<LogicalTypeID>{operandTypeID}, LogicalTypeID::UINT8, execFunc));
    }
    return functionSet;
}

} // namespace function
} // namespace lbug
