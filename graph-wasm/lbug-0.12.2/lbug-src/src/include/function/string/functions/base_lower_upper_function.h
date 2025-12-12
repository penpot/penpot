#pragma once

#include "common/api.h"
#include "common/types/ku_string.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct BaseLowerUpperFunction {

    LBUG_API static void operation(common::ku_string_t& input, common::ku_string_t& result,
        common::ValueVector& resultValueVector, bool isUpper);

    static void convertCharCase(char* result, const char* input, int32_t charPos, bool toUpper,
        int& originalSize, int& newSize);
    static void convertCase(char* result, uint32_t len, char* input, bool toUpper);
    static uint32_t getResultLen(char* inputStr, uint32_t inputLen, bool isUpper);
};

} // namespace function
} // namespace lbug
