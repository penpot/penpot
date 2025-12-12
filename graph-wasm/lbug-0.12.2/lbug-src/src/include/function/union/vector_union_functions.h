#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct UnionValueFunction {
    static constexpr const char* name = "UNION_VALUE";

    static function_set getFunctionSet();
};

struct UnionTagFunction {
    static constexpr const char* name = "UNION_TAG";

    static function_set getFunctionSet();
};

struct UnionExtractFunction {
    static constexpr const char* name = "UNION_EXTRACT";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
