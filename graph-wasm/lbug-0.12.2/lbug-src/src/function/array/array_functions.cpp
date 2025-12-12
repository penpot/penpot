#include "binder/expression/literal_expression.h"
#include "common/exception/binder.h"
#include "function/array/functions/array_cosine_similarity.h"
#include "function/array/functions/array_cross_product.h"
#include "function/array/functions/array_distance.h"
#include "function/array/functions/array_inner_product.h"
#include "function/array/functions/array_squared_distance.h"
#include "function/array/vector_array_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

static LogicalType interpretLogicalType(const binder::Expression* expr) {
    if (expr->expressionType == ExpressionType::LITERAL &&
        expr->dataType.getLogicalTypeID() == LogicalTypeID::LIST) {
        auto numChildren =
            expr->constPtrCast<binder::LiteralExpression>()->getValue().getChildrenSize();
        return LogicalType::ARRAY(ListType::getChildType(expr->dataType).copy(), numChildren);
    }
    return expr->dataType.copy();
}

std::unique_ptr<FunctionBindData> ArrayCrossProductBindFunc(const ScalarBindFuncInput& input) {
    auto leftType = interpretLogicalType(input.arguments[0].get());
    auto rightType = interpretLogicalType(input.arguments[1].get());
    if (leftType != rightType) {
        throw BinderException(
            stringFormat("{} requires both arrays to have the same element type and size of 3",
                ArrayCrossProductFunction::name));
    }
    scalar_func_exec_t execFunc;
    switch (ArrayType::getChildType(leftType).getLogicalTypeID()) {
    case LogicalTypeID::INT128:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<int128_t>>;
        break;
    case LogicalTypeID::INT64:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<int64_t>>;
        break;
    case LogicalTypeID::INT32:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<int32_t>>;
        break;
    case LogicalTypeID::INT16:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<int16_t>>;
        break;
    case LogicalTypeID::INT8:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<int8_t>>;
        break;
    case LogicalTypeID::FLOAT:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<float>>;
        break;
    case LogicalTypeID::DOUBLE:
        execFunc = ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t,
            list_entry_t, ArrayCrossProduct<double>>;
        break;
    default:
        throw BinderException{
            stringFormat("{} can only be applied on array of floating points or signed integers",
                ArrayCrossProductFunction::name)};
    }
    input.definition->ptrCast<ScalarFunction>()->execFunc = execFunc;
    const auto resultType = LogicalType::ARRAY(ArrayType::getChildType(leftType).copy(),
        ArrayType::getNumElements(leftType));
    return FunctionBindData::getSimpleBindData(input.arguments, resultType);
}

function_set ArrayCrossProductFunction::getFunctionSet() {
    function_set result;
    auto func = std::make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{
            LogicalTypeID::ARRAY,
            LogicalTypeID::ARRAY,
        },
        LogicalTypeID::ARRAY);
    func->bindFunc = ArrayCrossProductBindFunc;
    result.push_back(std::move(func));
    return result;
}

static LogicalType getChildType(const LogicalType& type) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::ARRAY:
        return ArrayType::getChildType(type).copy();
    case LogicalTypeID::LIST:
        return ListType::getChildType(type).copy();
        // LCOV_EXCL_START
    default:
        throw BinderException(stringFormat(
            "Cannot retrieve child type of type {}. LIST or ARRAY is expected.", type.toString()));
        // LCOV_EXCL_STOP
    }
}

static void validateChildType(const LogicalType& type, const std::string& functionName) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::DOUBLE:
    case LogicalTypeID::FLOAT:
        return;
    default:
        throw BinderException(
            stringFormat("{} requires argument type to be FLOAT[] or DOUBLE[].", functionName));
    }
}

static LogicalType validateArrayFunctionParameters(const LogicalType& leftType,
    const LogicalType& rightType, const std::string& functionName) {
    auto leftChildType = getChildType(leftType);
    auto rightChildType = getChildType(rightType);
    validateChildType(leftChildType, functionName);
    validateChildType(rightChildType, functionName);
    if (leftType.getLogicalTypeID() == common::LogicalTypeID::ARRAY) {
        return leftType.copy();
    } else if (rightType.getLogicalTypeID() == common::LogicalTypeID::ARRAY) {
        return rightType.copy();
    }
    throw BinderException(
        stringFormat("{} requires at least one argument to be ARRAY but all parameters are LIST.",
            functionName));
}

template<typename OPERATION, typename RESULT>
static scalar_func_exec_t getBinaryArrayExecFuncSwitchResultType() {
    auto execFunc =
        ScalarFunction::BinaryExecListStructFunction<list_entry_t, list_entry_t, RESULT, OPERATION>;
    return execFunc;
}

template<typename OPERATION>
scalar_func_exec_t getScalarExecFunc(LogicalType type) {
    scalar_func_exec_t execFunc;
    switch (ArrayType::getChildType(type).getLogicalTypeID()) {
    case LogicalTypeID::FLOAT:
        execFunc = getBinaryArrayExecFuncSwitchResultType<OPERATION, float>();
        break;
    case LogicalTypeID::DOUBLE:
        execFunc = getBinaryArrayExecFuncSwitchResultType<OPERATION, double>();
        break;
    default:
        KU_UNREACHABLE;
    }
    return execFunc;
}

template<typename OPERATION>
std::unique_ptr<FunctionBindData> arrayTemplateBindFunc(std::string functionName,
    ScalarBindFuncInput input) {
    auto leftType = interpretLogicalType(input.arguments[0].get());
    auto rightType = interpretLogicalType(input.arguments[1].get());
    auto paramType = validateArrayFunctionParameters(leftType, rightType, functionName);
    input.definition->ptrCast<ScalarFunction>()->execFunc =
        std::move(getScalarExecFunc<OPERATION>(paramType.copy()));
    auto bindData = std::make_unique<FunctionBindData>(ArrayType::getChildType(paramType).copy());
    std::vector<LogicalType> paramTypes;
    for (auto& _ : input.arguments) {
        (void)_;
        bindData->paramTypes.push_back(paramType.copy());
    }
    return bindData;
}

template<typename OPERATION>
function_set templateGetFunctionSet(const std::string& functionName) {
    function_set result;
    auto function = std::make_unique<ScalarFunction>(functionName,
        std::vector<LogicalTypeID>{
            LogicalTypeID::ARRAY,
            LogicalTypeID::ARRAY,
        },
        LogicalTypeID::ANY);
    function->bindFunc =
        std::bind(arrayTemplateBindFunc<OPERATION>, functionName, std::placeholders::_1);
    result.push_back(std::move(function));
    return result;
}

function_set ArrayCosineSimilarityFunction::getFunctionSet() {
    return templateGetFunctionSet<ArrayCosineSimilarity>(name);
}

function_set ArrayDistanceFunction::getFunctionSet() {
    return templateGetFunctionSet<ArrayDistance>(name);
}

function_set ArraySquaredDistanceFunction::getFunctionSet() {
    return templateGetFunctionSet<ArraySquaredDistance>(name);
}

function_set ArrayInnerProductFunction::getFunctionSet() {
    return templateGetFunctionSet<ArrayInnerProduct>(name);
}

function_set ArrayDotProductFunction::getFunctionSet() {
    return templateGetFunctionSet<ArrayInnerProduct>(name);
}

} // namespace function
} // namespace lbug
