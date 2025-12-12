#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct OctetLengthFunctions {
    static constexpr const char* name = "OCTET_LENGTH";

    static function_set getFunctionSet();
};

struct EncodeFunctions {
    static constexpr const char* name = "ENCODE";

    static function_set getFunctionSet();
};

struct DecodeFunctions {
    static constexpr const char* name = "DECODE";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
