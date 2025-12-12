#pragma once

#include "common/types/ku_string.h"
#include "function/list/functions/list_len_function.h"
#include "substr_function.h"

namespace lbug {
namespace function {

struct Right {
public:
    static inline void operation(common::ku_string_t& left, int64_t& right,
        common::ku_string_t& result, common::ValueVector& resultValueVector) {
        int64_t leftLen = 0;
        ListLen::operation(left, leftLen);
        int64_t len =
            (right > -1) ? std::min(leftLen, right) : std::max(leftLen + right, (int64_t)0);
        SubStr::operation(left, leftLen - len + 1, len, result, resultValueVector);
    }
};

} // namespace function
} // namespace lbug
