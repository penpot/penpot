#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct CoalesceFunction {
    static constexpr const char* name = "COALESCE";

    static function_set getFunctionSet();
};

struct IfNullFunction {
    static constexpr const char* name = "IFNULL";

    static function_set getFunctionSet();
};

struct ConstantOrNullFunction {
    static constexpr const char* name = "CONSTANT_OR_NULL";

    static function_set getFunctionSet();
};

struct CountIfFunction {
    static constexpr const char* name = "COUNT_IF";

    static function_set getFunctionSet();
};

struct ErrorFunction {
    static constexpr const char* name = "ERROR";

    static function_set getFunctionSet();
};

struct NullIfFunction {
    static constexpr const char* name = "NULLIF";

    static function_set getFunctionSet();
};

struct TypeOfFunction {
    static constexpr const char* name = "TYPEOF";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
