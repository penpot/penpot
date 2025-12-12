#include "common/type_utils.h"
#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct ListAnyValue {
    template<typename T>
    static void operation(common::list_entry_t& input, T& result, common::ValueVector& inputVector,
        common::ValueVector& resultVector) {
        auto inputValues = common::ListVector::getListValues(&inputVector, input);
        auto inputDataVector = common::ListVector::getDataVector(&inputVector);
        auto numBytesPerValue = inputDataVector->getNumBytesPerValue();

        for (auto i = 0u; i < input.size; i++) {
            if (!(inputDataVector->isNull(input.offset + i))) {
                resultVector.copyFromVectorData(reinterpret_cast<uint8_t*>(&result),
                    inputDataVector, inputValues);
                break;
            }
            inputValues += numBytesPerValue;
        }
    }
};

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    auto scalarFunction = ku_dynamic_cast<ScalarFunction*>(input.definition);
    const auto& resultType = ListType::getChildType(input.arguments[0]->dataType);
    TypeUtils::visit(resultType.getPhysicalType(), [&scalarFunction]<typename T>(T) {
        scalarFunction->execFunc =
            ScalarFunction::UnaryExecNestedTypeFunction<list_entry_t, T, ListAnyValue>;
    });
    return FunctionBindData::getSimpleBindData(input.arguments, resultType);
}

function_set ListAnyValueFunction::getFunctionSet() {
    function_set result;
    auto function = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST}, LogicalTypeID::ANY);
    function->bindFunc = bindFunc;
    result.push_back(std::move(function));
    return result;
}

} // namespace function
} // namespace lbug
