#include "function/list/functions/list_concat_function.h"

#include "common/exception/binder.h"
#include "common/exception/message.h"
#include "function/list/functions/list_function_utils.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

void ListConcat::operation(common::list_entry_t& left, common::list_entry_t& right,
    common::list_entry_t& result, common::ValueVector& leftVector, common::ValueVector& rightVector,
    common::ValueVector& resultVector) {
    result = common::ListVector::addList(&resultVector, left.size + right.size);
    auto resultDataVector = common::ListVector::getDataVector(&resultVector);
    auto resultPos = result.offset;
    auto leftDataVector = common::ListVector::getDataVector(&leftVector);
    auto leftPos = left.offset;
    for (auto i = 0u; i < left.size; i++) {
        resultDataVector->copyFromVectorData(resultPos++, leftDataVector, leftPos++);
    }
    auto rightDataVector = common::ListVector::getDataVector(&rightVector);
    auto rightPos = right.offset;
    for (auto i = 0u; i < right.size; i++) {
        resultDataVector->copyFromVectorData(resultPos++, rightDataVector, rightPos++);
    }
}

std::unique_ptr<FunctionBindData> ListConcatFunction::bindFunc(const ScalarBindFuncInput& input) {
    std::vector<LogicalType> types;
    types.push_back(input.arguments[0]->getDataType().copy());
    types.push_back(input.arguments[1]->getDataType().copy());

    using resolver = ListTypeResolver<ListOp::Concat>;
    ListFunctionUtils::resolveTypes(input, types, resolver::leftEmpty, resolver::leftEmpty,
        resolver::rightEmpty, resolver::finalResolver, resolver::bothNull, resolver::leftEmpty,
        resolver::rightEmpty, resolver::finalResolver);

    if (types[0] != types[1]) {
        throw BinderException(ExceptionMessage::listFunctionIncompatibleChildrenType(name,
            types[0].toString(), types[1].toString()));
    }
    return std::make_unique<FunctionBindData>(std::move(types), types[0].copy());
}

function_set ListConcatFunction::getFunctionSet() {
    function_set result;
    auto execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
        list_entry_t, ListConcat>;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::LIST}, LogicalTypeID::LIST,
        execFunc);
    function->bindFunc = bindFunc;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
