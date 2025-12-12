#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct InternalIDCreationFunction {
    static constexpr const char* name = "internal_id";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
