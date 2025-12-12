#include "common/exception/binder.h"
#include "common/exception/message.h"
#include "common/type_utils.h"
#include "function/list/functions/list_position_function.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct ListHasAll {
    static void operation(common::list_entry_t& left, common::list_entry_t& right, uint8_t& result,
        common::ValueVector& leftVector, common::ValueVector& rightVector,
        common::ValueVector& resultVector) {
        int64_t pos = 0;
        auto rightDataVector = ListVector::getDataVector(&rightVector);
        result = true;
        for (auto i = 0u; i < right.size; i++) {
            common::TypeUtils::visit(ListType::getChildType(rightVector.dataType).getPhysicalType(),
                [&]<typename T>(T) {
                    if (rightDataVector->isNull(right.offset + i)) {
                        return;
                    }
                    ListPosition::operation(left,
                        *(T*)ListVector::getListValuesWithOffset(&rightVector, right, i), pos,
                        leftVector, *ListVector::getDataVector(&rightVector), resultVector);
                    result = (pos != 0);
                });
            if (!result) {
                return;
            }
        }
    }
};

std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    std::vector<LogicalType> types;
    for (auto& arg : input.arguments) {
        if (arg->dataType == LogicalType::ANY()) {
            types.push_back(LogicalType::LIST(LogicalType::INT64()));
        } else {
            types.push_back(arg->dataType.copy());
        }
    }
    if (types[0] != types[1]) {
        throw common::BinderException(ExceptionMessage::listFunctionIncompatibleChildrenType(
            ListHasAllFunction::name, input.arguments[0]->getDataType().toString(),
            input.arguments[1]->getDataType().toString()));
    }
    return std::make_unique<FunctionBindData>(std::move(types), LogicalType::BOOL());
}

function_set ListHasAllFunction::getFunctionSet() {
    function_set result;
    auto execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
        uint8_t, ListHasAll>;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::LIST}, LogicalTypeID::BOOL,
        execFunc);
    function->bindFunc = bindFunc;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
