#include "function/schema/vector_node_rel_functions.h"

#include "common/vector/value_vector.h"
#include "function/scalar_function.h"
#include "function/schema/offset_functions.h"
#include "function/unary_function_executor.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>& params,
    const std::vector<common::SelectionVector*>& paramSelVectors, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* /*dataPtr*/ = nullptr) {
    KU_ASSERT(params.size() == 1);
    UnaryFunctionExecutor::execute<internalID_t, int64_t, Offset>(*params[0], paramSelVectors[0],
        result, resultSelVector);
}

function_set OffsetFunction::getFunctionSet() {
    function_set functionSet;
    functionSet.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::INTERNAL_ID}, LogicalTypeID::INT64, execFunc));
    return functionSet;
}

} // namespace function
} // namespace lbug
