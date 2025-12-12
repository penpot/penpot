#include "binder/expression/expression_util.h"
#include "function/path/vector_path_functions.h"
#include "function/scalar_function.h"
#include "function/struct/vector_struct_functions.h"

using namespace lbug::common;
using namespace lbug::binder;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    const auto& structType = input.arguments[0]->getDataType();
    auto fieldIdx = StructType::getFieldIdx(structType, InternalKeyword::NODES);
    auto resultType = StructType::getField(structType, fieldIdx).getType().copy();
    auto bindData = std::make_unique<StructExtractBindData>(std::move(resultType), fieldIdx);
    bindData->paramTypes = ExpressionUtil::getDataTypes(input.arguments);
    return bindData;
}

function_set NodesFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::RECURSIVE_REL}, LogicalTypeID::ANY);
    function->bindFunc = bindFunc;
    function->compileFunc = StructExtractFunctions::compileFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
