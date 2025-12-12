#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

enum class ListOp { Append, Prepend, Concat };

template<ListOp op>
struct ListTypeResolver;

template<>
struct ListTypeResolver<ListOp::Append> {
    static void anyEmpty(std::vector<common::LogicalType>& types, common::LogicalType& targetType);
    static void bothNull(std::vector<common::LogicalType>& types, common::LogicalType& targetType);
    static void leftNull(std::vector<common::LogicalType>& types, common::LogicalType& targetType);
    static void rightNull(std::vector<common::LogicalType>& types, common::LogicalType& targetType);
    static void finalResolver(std::vector<common::LogicalType>& types,
        common::LogicalType& targetType);
};

template<>
struct ListTypeResolver<ListOp::Prepend>
    : ListTypeResolver<ListOp::Append> { /*Prepend empty list resolution follows the same logic as
                                            the Append operation*/
};

template<>
struct ListTypeResolver<ListOp::Concat> {
    static void leftEmpty(std::vector<common::LogicalType>& types, common::LogicalType& targetType);
    static void rightEmpty(std::vector<common::LogicalType>& types,
        common::LogicalType& targetType);
    static void bothNull(std::vector<common::LogicalType>& types, common::LogicalType& targetType);
    static void finalResolver(std::vector<common::LogicalType>& types,
        common::LogicalType& targetType);
};

struct ListFunctionUtils {
public:
    using type_resolver = std::function<void(std::vector<common::LogicalType>& types,
        common::LogicalType& targetType)>;

    static void resolveEmptyList(const ScalarBindFuncInput& input,
        std::vector<common::LogicalType>& types, type_resolver bothEmpty, type_resolver leftEmpty,
        type_resolver rightEmpty, type_resolver finalEmptyListResolver);

    static void resolveNulls(std::vector<common::LogicalType>& types, type_resolver bothNull,
        type_resolver leftNull, type_resolver rightNull, type_resolver finalNullParamResolver);

    static void resolveTypes(const ScalarBindFuncInput& input,
        std::vector<common::LogicalType>& types, type_resolver bothEmpty, type_resolver leftEmpty,
        type_resolver rightEmpty, type_resolver finalEmptyListResolver, type_resolver bothNull,
        type_resolver leftNull, type_resolver rightNull, type_resolver finalNullParamResolver);
};

} // namespace function
} // namespace lbug
