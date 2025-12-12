#include "binder/expression/expression_util.h"
#include "binder/expression/literal_expression.h"
#include "common/exception/binder.h"
#include "function/scalar_function.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace function {

std::unique_ptr<FunctionBindData> StructExtractFunctions::bindFunc(
    const ScalarBindFuncInput& input) {
    const auto& structType = input.arguments[0]->getDataType();
    if (input.arguments[1]->expressionType != ExpressionType::LITERAL) {
        throw BinderException("Key name for struct/union extract must be STRING literal.");
    }
    auto key =
        input.arguments[1]->constPtrCast<LiteralExpression>()->getValue().getValue<std::string>();
    auto fieldIdx = StructType::getFieldIdx(structType, key);
    if (fieldIdx == INVALID_STRUCT_FIELD_IDX) {
        throw BinderException(stringFormat("Invalid struct field name: {}.", key));
    }
    auto paramTypes = ExpressionUtil::getDataTypes(input.arguments);
    auto resultType = StructType::getField(structType, fieldIdx).getType().copy();
    auto bindData = std::make_unique<StructExtractBindData>(std::move(resultType), fieldIdx);
    bindData->paramTypes.push_back(input.arguments[0]->getDataType().copy());
    bindData->paramTypes.push_back(LogicalType(input.definition->parameterTypeIDs[1]));
    return bindData;
}

void StructExtractFunctions::compileFunc(FunctionBindData* bindData,
    const std::vector<std::shared_ptr<ValueVector>>& parameters,
    std::shared_ptr<ValueVector>& result) {
    KU_ASSERT(parameters[0]->dataType.getPhysicalType() == PhysicalTypeID::STRUCT);
    auto& structBindData = bindData->cast<StructExtractBindData>();
    result = StructVector::getFieldVector(parameters[0].get(), structBindData.childIdx);
    result->state = parameters[0]->state;
}

static std::unique_ptr<ScalarFunction> getStructExtractFunction(LogicalTypeID logicalTypeID) {
    auto function = std::make_unique<ScalarFunction>(StructExtractFunctions::name,
        std::vector<LogicalTypeID>{logicalTypeID, LogicalTypeID::STRING}, LogicalTypeID::ANY);
    function->bindFunc = StructExtractFunctions::bindFunc;
    function->compileFunc = StructExtractFunctions::compileFunc;
    return function;
}

function_set StructExtractFunctions::getFunctionSet() {
    function_set functions;
    auto inputTypeIDs =
        std::vector<LogicalTypeID>{LogicalTypeID::STRUCT, LogicalTypeID::NODE, LogicalTypeID::REL};
    for (auto inputTypeID : inputTypeIDs) {
        functions.push_back(getStructExtractFunction(inputTypeID));
    }
    return functions;
}

} // namespace function
} // namespace lbug
