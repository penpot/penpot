#include "common/exception/binder.h"
#include "common/exception/message.h"
#include "common/type_utils.h"
#include "common/types/types.h"
#include "function/list/functions/list_function_utils.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct ListPrepend {
    template<typename T>
    static void operation(common::list_entry_t& listEntry, T& value, common::list_entry_t& result,
        common::ValueVector& listVector, common::ValueVector& valueVector,
        common::ValueVector& resultVector) {
        result = common::ListVector::addList(&resultVector, listEntry.size + 1);
        auto resultDataVector = common::ListVector::getDataVector(&resultVector);
        resultDataVector->copyFromVectorData(
            common::ListVector::getListValues(&resultVector, result), &valueVector,
            reinterpret_cast<uint8_t*>(&value));
        auto resultPos = result.offset + 1;
        auto listDataVector = common::ListVector::getDataVector(&listVector);
        auto listPos = listEntry.offset;
        for (auto i = 0u; i < listEntry.size; i++) {
            resultDataVector->copyFromVectorData(resultPos++, listDataVector, listPos++);
        }
    }
};

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {

    std::vector<LogicalType> types;
    types.push_back(input.arguments[0]->getDataType().copy());
    types.push_back(input.arguments[1]->getDataType().copy());

    using resolver = ListTypeResolver<ListOp::Prepend>;
    ListFunctionUtils::resolveTypes(input, types, resolver::anyEmpty, resolver::anyEmpty,
        resolver::anyEmpty, resolver::finalResolver, resolver::bothNull, resolver::leftNull,
        resolver::rightNull, resolver::finalResolver);

    if (types[0].getLogicalTypeID() != LogicalTypeID::ANY &&
        types[1] != ListType::getChildType(types[0])) {
        throw BinderException(ExceptionMessage::listFunctionIncompatibleChildrenType(
            ListAppendFunction::name, types[0].toString(), types[1].toString()));
    }

    auto scalarFunction = input.definition->ptrCast<ScalarFunction>();
    TypeUtils::visit(types[1].getPhysicalType(), [&scalarFunction]<typename T>(T) {
        scalarFunction->execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, T,
            list_entry_t, ListPrepend>;
    });

    return std::make_unique<FunctionBindData>(std::move(types), types[0].copy());
}

function_set ListPrependFunction::getFunctionSet() {
    function_set result;
    auto func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::ANY}, LogicalTypeID::LIST);
    func->bindFunc = bindFunc;
    result.push_back(std::move(func));
    return result;
}

} // namespace function
} // namespace lbug
