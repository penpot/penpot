#include "common/type_utils.h"
#include "common/types/types.h"
#include "function/internal_id/vector_internal_id_functions.h"
#include "function/scalar_function.h"

namespace lbug {
namespace function {

using namespace common;

struct InternalIDCreation {
    template<typename T>
    static void operation(T& tableID, T& offset, internalID_t& result) {
        result = internalID_t((offset_t)offset, (offset_t)tableID);
    }
};

function_set InternalIDCreationFunction::getFunctionSet() {
    function_set result;
    function::scalar_func_exec_t execFunc;
    for (auto typeID : LogicalTypeUtils::getNumericalLogicalTypeIDs()) {
        common::TypeUtils::visit(
            common::LogicalType(typeID),
            [&]<common::NumericTypes T>(T) {
                execFunc =
                    ScalarFunction::BinaryExecFunction<T, T, internalID_t, InternalIDCreation>;
            },
            [](auto) { KU_UNREACHABLE; });
        result.push_back(std::make_unique<ScalarFunction>(name,
            std::vector<common::LogicalTypeID>{typeID, typeID}, LogicalTypeID::INTERNAL_ID,
            execFunc));
    }
    return result;
}

} // namespace function
} // namespace lbug
