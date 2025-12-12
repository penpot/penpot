#pragma once

#include "base_str_function.h"
#include "common/types/ku_string.h"

namespace lbug {
namespace function {

struct Ltrim {
    static inline void operation(common::ku_string_t& input, common::ku_string_t& result,
        common::ValueVector& resultValueVector) {
        BaseStrOperation::operation(input, result, resultValueVector, ltrim);
    }

    static uint32_t ltrim(char* data, uint32_t len) {
        auto counter = 0u;
        for (; counter < len; counter++) {
            if (!isspace(data[counter])) {
                break;
            }
        }
        for (uint32_t i = 0; i < len - counter; i++) {
            data[i] = data[i + counter];
        }
        return len - counter;
    }
};

} // namespace function
} // namespace lbug
