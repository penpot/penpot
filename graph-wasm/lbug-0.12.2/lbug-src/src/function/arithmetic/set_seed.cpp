#include "common/random_engine.h"
#include "function/arithmetic/vector_arithmetic_functions.h"
#include "function/scalar_function.h"

namespace lbug {
namespace function {

using namespace lbug::common;

struct SetSeed {
    static void operation(double& seed, void* dataPtr) {
        auto context = reinterpret_cast<FunctionBindData*>(dataPtr)->clientContext;
        RandomEngine::Get(*context)->setSeed(
            static_cast<uint64_t>(seed * static_cast<double>(UINT64_MAX)));
    }
};

function_set SetSeedFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        std::make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::DOUBLE},
            LogicalTypeID::INT32, ScalarFunction::UnarySetSeedFunction<double, int, SetSeed>));
    return result;
}

} // namespace function
} // namespace lbug
