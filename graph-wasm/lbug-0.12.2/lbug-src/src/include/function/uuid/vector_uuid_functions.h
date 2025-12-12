#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct GenRandomUUIDFunction {
    static constexpr const char* name = "GEN_RANDOM_UUID";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
