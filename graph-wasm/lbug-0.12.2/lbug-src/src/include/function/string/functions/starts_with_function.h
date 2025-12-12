#pragma once

#include "common/types/ku_string.h"

namespace lbug {
namespace function {

struct StartsWith {
    static inline void operation(common::ku_string_t& left, common::ku_string_t& right,
        uint8_t& result) {
        auto lStr = left.getAsString();
        auto rStr = right.getAsString();
        result = lStr.starts_with(rStr);
    }
};

} // namespace function
} // namespace lbug
