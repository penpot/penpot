#include "function/list/functions/list_sort_function.h"

#include "common/exception/binder.h"
#include "common/exception/runtime.h"
#include "common/type_utils.h"
#include "function/list/functions/list_reverse_sort_function.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

template<typename T>
static scalar_func_exec_t getListSortExecFunction(const binder::expression_vector& arguments) {
    scalar_func_exec_t func;
    if (arguments.size() == 1) {
        func = ScalarFunction::UnaryExecNestedTypeFunction<list_entry_t, list_entry_t, T>;
    } else if (arguments.size() == 2) {
        func = ScalarFunction::BinaryExecListStructFunction<list_entry_t, ku_string_t, list_entry_t,
            T>;
    } else if (arguments.size() == 3) {
        func = ScalarFunction::TernaryExecListStructFunction<list_entry_t, ku_string_t, ku_string_t,
            list_entry_t, T>;
    } else {
        throw RuntimeException("Invalid number of arguments");
    }
    return func;
}

static std::unique_ptr<FunctionBindData> ListSortBindFunc(ScalarBindFuncInput input) {
    auto scalarFunction = input.definition->ptrCast<ScalarFunction>();
    if (input.arguments[0]->dataType.getLogicalTypeID() == common::LogicalTypeID::ANY) {
        throw BinderException(stringFormat("Cannot resolve recursive data type for expression {}",
            input.arguments[0]->toString()));
    }
    common::TypeUtils::visit(
        ListType::getChildType(input.arguments[0]->dataType).getPhysicalType(),
        [&input, &scalarFunction]<ComparableTypes T>(T) {
            scalarFunction->execFunc = getListSortExecFunction<ListSort<T>>(input.arguments);
        },
        [](auto) { KU_UNREACHABLE; });
    return FunctionBindData::getSimpleBindData(input.arguments, input.arguments[0]->getDataType());
}

static std::unique_ptr<FunctionBindData> ListReverseSortBindFunc(const ScalarBindFuncInput& input) {
    auto scalarFunction = input.definition->ptrCast<ScalarFunction>();
    common::TypeUtils::visit(
        ListType::getChildType(input.arguments[0]->dataType).getPhysicalType(),
        [&input, &scalarFunction]<ComparableTypes T>(T) {
            scalarFunction->execFunc = getListSortExecFunction<ListReverseSort<T>>(input.arguments);
        },
        [](auto) { KU_UNREACHABLE; });
    return FunctionBindData::getSimpleBindData(input.arguments, input.arguments[0]->getDataType());
}

function_set ListSortFunction::getFunctionSet() {
    function_set result;
    std::unique_ptr<ScalarFunction> func;
    func = std::make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::LIST},
        LogicalTypeID::LIST);
    func->bindFunc = ListSortBindFunc;
    result.push_back(std::move(func));
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::STRING},
        LogicalTypeID::LIST);
    func->bindFunc = ListSortBindFunc;
    result.push_back(std::move(func));
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::STRING,
            LogicalTypeID::STRING},
        LogicalTypeID::LIST);
    func->bindFunc = ListSortBindFunc;
    result.push_back(std::move(func));
    return result;
}

function_set ListReverseSortFunction::getFunctionSet() {
    function_set result;
    std::unique_ptr<ScalarFunction> func;
    func = std::make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::LIST},
        LogicalTypeID::LIST);
    func->bindFunc = ListReverseSortBindFunc;
    result.push_back(std::move(func));
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::STRING},
        LogicalTypeID::LIST);
    func->bindFunc = ListReverseSortBindFunc;
    result.push_back(std::move(func));
    return result;
}

} // namespace function
} // namespace lbug
