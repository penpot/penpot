#include "function/map/functions/map_creation_function.h"

#include "function/map/vector_map_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    const auto& keyType = ListType::getChildType(input.arguments[0]->dataType);
    const auto& valueType = ListType::getChildType(input.arguments[1]->dataType);
    auto resultType = LogicalType::MAP(keyType.copy(), valueType.copy());
    return FunctionBindData::getSimpleBindData(input.arguments, resultType);
}

function_set MapCreationFunctions::getFunctionSet() {
    auto execFunc = ScalarFunction::BinaryExecWithBindData<list_entry_t, list_entry_t, list_entry_t,
        MapCreation>;
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::LIST}, LogicalTypeID::MAP,
        execFunc);
    function->bindFunc = bindFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
