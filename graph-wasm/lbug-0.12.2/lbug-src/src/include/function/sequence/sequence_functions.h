#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct CurrValFunction {
    static constexpr const char* name = "CURRVAL";

    static function_set getFunctionSet();
};

struct NextValFunction {
    static constexpr const char* name = "NEXTVAL";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
