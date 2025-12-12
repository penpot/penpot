#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct CenturyFunction {
    static constexpr const char* name = "CENTURY";

    static function_set getFunctionSet();
};

struct EpochMsFunction {
    static constexpr const char* name = "EPOCH_MS";

    static function_set getFunctionSet();
};

struct ToTimestampFunction {
    static constexpr const char* name = "TO_TIMESTAMP";

    static function_set getFunctionSet();
};

struct ToEpochMsFunction {
    static constexpr const char* name = "TO_EPOCH_MS";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
