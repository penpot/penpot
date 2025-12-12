#include "function/list/functions/list_function_utils.h"

#include "binder/expression/expression_util.h"

using namespace lbug::common;

namespace lbug {
namespace function {

void ListTypeResolver<ListOp::Append>::anyEmpty(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    targetType = types[1].copy();
    if (targetType.getLogicalTypeID() == LogicalTypeID::ANY) {
        targetType = LogicalType(LogicalTypeID::INT64);
    }
}

void ListTypeResolver<ListOp::Append>::bothNull(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    (void)types;
    targetType = LogicalType::INT64();
}

void ListTypeResolver<ListOp::Append>::leftNull(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    targetType = types[1].copy();
}

void ListTypeResolver<ListOp::Append>::rightNull(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    targetType = ListType::getChildType(types[0]).copy();
}

void ListTypeResolver<ListOp::Append>::finalResolver(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    types[0] = LogicalType::LIST(targetType.copy());
    types[1] = targetType.copy();
}

void ListTypeResolver<ListOp::Concat>::leftEmpty(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    targetType = types[1].copy();
}
void ListTypeResolver<ListOp::Concat>::rightEmpty(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    targetType = types[0].copy();
}
void ListTypeResolver<ListOp::Concat>::bothNull(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    (void)types;
    targetType = LogicalType::LIST(LogicalType::INT64());
}
void ListTypeResolver<ListOp::Concat>::finalResolver(std::vector<common::LogicalType>& types,
    common::LogicalType& targetType) {
    types[0] = targetType.copy();
    types[1] = targetType.copy();
}

void ListFunctionUtils::resolveEmptyList(const ScalarBindFuncInput& input,
    std::vector<common::LogicalType>& types, type_resolver bothEmpty, type_resolver leftEmpty,
    type_resolver rightEmpty, type_resolver finalEmptyListResolver) {

    auto isArg0Empty = binder::ExpressionUtil::isEmptyList(*input.arguments[0]);
    auto isArg1Empty = binder::ExpressionUtil::isEmptyList(*input.arguments[1]);
    LogicalType targetType;
    if (isArg0Empty && isArg1Empty) {
        bothEmpty(types, targetType);
    } else if (isArg0Empty) {
        leftEmpty(types, targetType);
    } else if (isArg1Empty) {
        rightEmpty(types, targetType);
    } else {
        return;
    }
    finalEmptyListResolver(types, targetType);
}

void ListFunctionUtils::resolveNulls(std::vector<common::LogicalType>& types,
    type_resolver bothNull, type_resolver leftNull, type_resolver rightNull,
    type_resolver finalNullParamResolver) {
    auto isArg0AnyType = types[0].getLogicalTypeID() == common::LogicalTypeID::ANY;
    auto isArg1AnyType = types[1].getLogicalTypeID() == common::LogicalTypeID::ANY;

    common::LogicalType targetType;
    if (isArg0AnyType && isArg1AnyType) {
        bothNull(types, targetType);
    } else if (isArg0AnyType) {
        leftNull(types, targetType);
    } else if (isArg1AnyType) {
        rightNull(types, targetType);
    } else {
        return;
    }
    finalNullParamResolver(types, targetType);
}

void ListFunctionUtils::resolveTypes(const ScalarBindFuncInput& input,
    std::vector<common::LogicalType>& types, type_resolver bothEmpty, type_resolver leftEmpty,
    type_resolver rightEmpty, type_resolver finalEmptyListResolver, type_resolver bothNull,
    type_resolver leftNull, type_resolver rightNull, type_resolver finalNullParamResolver) {
    resolveEmptyList(input, types, bothEmpty, leftEmpty, rightEmpty, finalEmptyListResolver);
    resolveNulls(types, bothNull, leftNull, rightNull, finalNullParamResolver);
}

} // namespace function
} // namespace lbug
