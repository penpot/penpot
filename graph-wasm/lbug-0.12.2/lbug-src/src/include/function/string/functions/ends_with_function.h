#pragma once

#include "common/types/ku_string.h"

namespace lbug {
namespace function {

struct EndsWith {
    static inline void operation(common::ku_string_t& left, common::ku_string_t& right,
        uint8_t& result) {
        if (right.len > left.len) {
            result = 0;
            return;
        }
        auto lenDiff = left.len - right.len;
        auto lData = left.getData();
        auto rData = right.getData();
        for (auto i = 0u; i < right.len; i++) {
            if (rData[i] != lData[lenDiff + i]) {
                result = 0;
                return;
            }
        }
        result = 1;
    }
};

} // namespace function
} // namespace lbug
