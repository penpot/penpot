#include "function/uuid/vector_uuid_functions.h"

#include "function/scalar_function.h"
#include "function/uuid/functions/gen_random_uuid.h"

using namespace lbug::common;

namespace lbug {
namespace function {

function_set GenRandomUUIDFunction::getFunctionSet() {
    function_set definitions;
    definitions.push_back(
        make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{}, LogicalTypeID::UUID,
            ScalarFunction::NullaryAuxilaryExecFunction<ku_uuid_t, GenRandomUUID>));
    return definitions;
}

} // namespace function
} // namespace lbug
