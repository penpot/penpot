#include "function/scalar_function.h"
#include "function/union/vector_union_functions.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    KU_ASSERT(input.arguments.size() == 1);
    std::vector<StructField> fields;
    if (input.arguments[0]->getDataType().getLogicalTypeID() == common::LogicalTypeID::ANY) {
        input.arguments[0]->cast(LogicalType::STRING());
    }
    fields.emplace_back(input.arguments[0]->getAlias(), input.arguments[0]->getDataType().copy());
    auto resultType = LogicalType::UNION(std::move(fields));
    return FunctionBindData::getSimpleBindData(input.arguments, resultType);
}

static void execFunc(const std::vector<std::shared_ptr<common::ValueVector>>&,
    const std::vector<common::SelectionVector*>&, common::ValueVector& result,
    common::SelectionVector* resultSelVector, void* /*dataPtr*/) {
    UnionVector::setTagField(result, *resultSelVector, UnionType::TAG_FIELD_IDX);
}

static void valueCompileFunc(FunctionBindData* /*bindData*/,
    const std::vector<std::shared_ptr<ValueVector>>& parameters,
    std::shared_ptr<ValueVector>& result) {
    KU_ASSERT(parameters.size() == 1);
    result->setState(parameters[0]->state);
    UnionVector::getTagVector(result.get())->setState(parameters[0]->state);
    UnionVector::referenceVector(result.get(), UnionType::TAG_FIELD_IDX, parameters[0]);
}

function_set UnionValueFunction::getFunctionSet() {
    function_set functionSet;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ANY}, LogicalTypeID::UNION, execFunc);
    function->bindFunc = bindFunc;
    function->compileFunc = valueCompileFunc;
    functionSet.push_back(std::move(function));
    return functionSet;
}

} // namespace function
} // namespace lbug
