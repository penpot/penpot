#include "common/random_engine.h"
#include "function/arithmetic/vector_arithmetic_functions.h"
#include "function/scalar_function.h"
#include "main/client_context.h"

namespace lbug {
namespace function {

using namespace lbug::common;

struct Rand {
    static void operation(double& result, void* dataPtr) {
        auto context = reinterpret_cast<FunctionBindData*>(dataPtr)->clientContext;
        result = static_cast<double>(RandomEngine::Get(*context)->nextRandomInteger()) /
                 static_cast<double>(UINT32_MAX);
    }
};

function_set RandFunction::getFunctionSet() {
    function_set result;
    result.push_back(std::make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{},
        LogicalTypeID::DOUBLE, ScalarFunction::NullaryAuxilaryExecFunction<double, Rand>));
    return result;
}

} // namespace function
} // namespace lbug
