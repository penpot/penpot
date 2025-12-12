#include "function/list/vector_list_functions.h"
#include "function/scalar_function.h"
#include "function/string/functions/substr_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static void normalizeIndices(int64_t& startIdx, int64_t& endIdx, uint64_t size) {
    if (startIdx < 0) {
        startIdx = size + startIdx + 1;
    }
    if (startIdx <= 0) {
        startIdx = 1;
    }
    if (endIdx < 0) {
        endIdx = size + endIdx + 1;
    }
    if (endIdx > (int64_t)size) {
        endIdx = size;
    }
    if (endIdx < startIdx) {
        startIdx = 1;
        endIdx = 0;
    }
}

struct ListSlice {
    // Note: this function takes in a 1-based begin/end index (The index of the first value in the
    // listEntry is 1).
    static void operation(list_entry_t& listEntry, int64_t& begin, int64_t& end,
        list_entry_t& result, ValueVector& listVector, ValueVector& resultVector) {
        auto startIdx = begin;
        auto endIdx = end;
        normalizeIndices(startIdx, endIdx, listEntry.size);
        result = ListVector::addList(&resultVector, endIdx - startIdx + 1);
        auto srcDataVector = ListVector::getDataVector(&listVector);
        auto srcPos = listEntry.offset + startIdx - 1;
        auto dstDataVector = ListVector::getDataVector(&resultVector);
        auto dstPos = result.offset;
        for (; startIdx <= endIdx; startIdx++) {
            dstDataVector->copyFromVectorData(dstPos++, srcDataVector, srcPos++);
        }
    }

    static void operation(ku_string_t& str, int64_t& begin, int64_t& end, ku_string_t& result,
        ValueVector&, ValueVector& resultValueVector) {
        auto startIdx = begin;
        auto endIdx = end;
        normalizeIndices(startIdx, endIdx, str.len);
        SubStr::operation(str, startIdx, endIdx - startIdx + 1, result, resultValueVector);
    }
};

static std::unique_ptr<FunctionBindData> bindFunc(const ScalarBindFuncInput& input) {
    KU_ASSERT(input.arguments.size() == 3);
    std::vector<LogicalType> paramTypes;
    auto& arg0Type = input.arguments[0]->getDataType();
    LogicalType resultType;
    switch (arg0Type.getLogicalTypeID()) {
    case LogicalTypeID::ANY: {
        paramTypes.push_back(LogicalType::STRING());
        resultType = LogicalType::STRING();
    } break;
    case LogicalTypeID::ARRAY: {
        paramTypes.push_back(arg0Type.copy());
        resultType = LogicalType::LIST(ArrayType::getChildType(arg0Type).copy());
    } break;
    default: {
        paramTypes.push_back(arg0Type.copy());
        resultType = arg0Type.copy();
    }
    }
    paramTypes.push_back(LogicalType(input.definition->parameterTypeIDs[1]));
    paramTypes.push_back(LogicalType(input.definition->parameterTypeIDs[2]));
    return std::make_unique<FunctionBindData>(std::move(paramTypes), std::move(resultType));
}

function_set ListSliceFunction::getFunctionSet() {
    function_set result;
    std::unique_ptr<ScalarFunction> func;
    // List slice
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::LIST, LogicalTypeID::INT64, LogicalTypeID::INT64},
        LogicalTypeID::LIST,
        ScalarFunction::TernaryExecListStructFunction<list_entry_t, int64_t, int64_t, list_entry_t,
            ListSlice>);
    func->bindFunc = bindFunc;
    result.push_back(std::move(func));
    // Array slice
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::ARRAY, LogicalTypeID::INT64,
            LogicalTypeID::INT64},
        LogicalTypeID::LIST,
        ScalarFunction::TernaryExecListStructFunction<list_entry_t, int64_t, int64_t, list_entry_t,
            ListSlice>);
    func->bindFunc = bindFunc;
    result.push_back(std::move(func));
    // Substr
    func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::INT64,
            LogicalTypeID::INT64},
        LogicalTypeID::STRING,
        ScalarFunction::TernaryExecListStructFunction<ku_string_t, int64_t, int64_t, ku_string_t,
            ListSlice>);
    func->bindFunc = bindFunc;
    result.push_back(std::move(func));
    return result;
}

} // namespace function
} // namespace lbug
