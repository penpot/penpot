#pragma once

#include "function/scalar_function.h"

namespace lbug {
namespace function {

/**
 *  In the system we define explicit cast and implicit cast.
 *  Explicit casts are performed from user function calls, e.g. date(), string().
 *  Implicit casts are added internally.
 */

struct CastChildFunctionExecutor;

template<typename T>
concept CastExecutor =
    std::is_same_v<T, UnaryFunctionExecutor> || std::is_same_v<T, CastChildFunctionExecutor>;

struct CastFunction {
    // This function is only used by expression binder when implicit cast is needed.
    // The expression binder should consider reusing the existing matchFunction() API.
    static bool hasImplicitCast(const common::LogicalType& srcType,
        const common::LogicalType& dstType);

    template<CastExecutor EXECUTOR = UnaryFunctionExecutor>
    static std::unique_ptr<ScalarFunction> bindCastFunction(const std::string& functionName,
        const common::LogicalType& sourceType, const common::LogicalType& targetType);
};

struct CastToDateFunction {
    static constexpr const char* name = "TO_DATE";

    static function_set getFunctionSet();
};

struct DateFunction {
    using alias = CastToDateFunction;

    static constexpr const char* name = "DATE";
};

struct CastToTimestampFunction {
    static constexpr const char* name = "TIMESTAMP";

    static function_set getFunctionSet();
};

struct CastToIntervalFunction {
    static constexpr const char* name = "TO_INTERVAL";

    static function_set getFunctionSet();
};

struct IntervalFunctionAlias {
    using alias = CastToIntervalFunction;

    static constexpr const char* name = "INTERVAL";
};

struct DurationFunction {
    using alias = CastToIntervalFunction;

    static constexpr const char* name = "DURATION";
};

struct CastToStringFunction {
    static constexpr const char* name = "TO_STRING";

    static function_set getFunctionSet();
};

struct StringFunction {
    using alias = CastToStringFunction;

    static constexpr const char* name = "STRING";
};

struct CastToBlobFunction {
    static constexpr const char* name = "TO_BLOB";

    static function_set getFunctionSet();
};

struct BlobFunction {
    using alias = CastToBlobFunction;

    static constexpr const char* name = "BLOB";
};

struct CastToUUIDFunction {
    static constexpr const char* name = "TO_UUID";

    static function_set getFunctionSet();
};

struct UUIDFunction {
    using alias = CastToUUIDFunction;

    static constexpr const char* name = "UUID";
};

struct CastToBoolFunction {
    static constexpr const char* name = "TO_BOOL";

    static function_set getFunctionSet();
};

struct CastToDoubleFunction {
    static constexpr const char* name = "TO_DOUBLE";

    static function_set getFunctionSet();
};

struct CastToFloatFunction {
    static constexpr const char* name = "TO_FLOAT";

    static function_set getFunctionSet();
};

struct CastToSerialFunction {
    static constexpr const char* name = "TO_SERIAL";

    static function_set getFunctionSet();
};

struct CastToInt128Function {
    static constexpr const char* name = "TO_INT128";

    static function_set getFunctionSet();
};

struct CastToInt64Function {
    static constexpr const char* name = "TO_INT64";

    static function_set getFunctionSet();
};

struct CastToInt32Function {
    static constexpr const char* name = "TO_INT32";

    static function_set getFunctionSet();
};

struct CastToInt16Function {
    static constexpr const char* name = "TO_INT16";

    static function_set getFunctionSet();
};

struct CastToInt8Function {
    static constexpr const char* name = "TO_INT8";

    static function_set getFunctionSet();
};

struct CastToUInt128Function {
    static constexpr const char* name = "TO_UINT128";

    static function_set getFunctionSet();
};

struct CastToUInt64Function {
    static constexpr const char* name = "TO_UINT64";

    static function_set getFunctionSet();
};

struct CastToUInt32Function {
    static constexpr const char* name = "TO_UINT32";

    static function_set getFunctionSet();
};

struct CastToUInt16Function {
    static constexpr const char* name = "TO_UINT16";

    static function_set getFunctionSet();
};

struct CastToUInt8Function {
    static constexpr const char* name = "TO_UINT8";

    static function_set getFunctionSet();
};

struct CastAnyFunction {
    static constexpr const char* name = "CAST";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
