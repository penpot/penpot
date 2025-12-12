#include "function/map/functions/map_extract_function.h"

#include "common/exception/runtime.h"
#include "common/type_utils.h"
#include "function/map/vector_map_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static void validateKeyType(const std::shared_ptr<binder::Expression>& mapExpression,
    const std::shared_ptr<binder::Expression>& extractKeyExpression) {
    const auto& mapKeyType = MapType::getKeyType(mapExpression->dataType);
    if (mapKeyType != extractKeyExpression->dataType) {
        throw RuntimeException("Unmatched map key type and extract key type");
    }
}

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    validateKeyType(input.arguments[0], input.arguments[1]);
    auto scalarFunction = ku_dynamic_cast<ScalarFunction*>(input.definition);
    TypeUtils::visit(input.arguments[1]->getDataType().getPhysicalType(), [&]<typename T>(T) {
        scalarFunction->execFunc =
            ScalarFunction::BinaryExecListStructFunction<list_entry_t, T, list_entry_t, MapExtract>;
    });
    auto resultType = LogicalType::LIST(MapType::getValueType(input.arguments[0]->dataType).copy());
    return FunctionBindData::getSimpleBindData(input.arguments, resultType);
}

function_set MapExtractFunctions::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::MAP, LogicalTypeID::ANY}, LogicalTypeID::LIST);
    function->bindFunc = bindFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
