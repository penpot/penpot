#pragma once

#include "common/types/ku_string.h"
#include "function/string/functions/base_regexp_function.h"
#include "re2.h"

namespace lbug {
namespace function {

struct RegexpMatches : BaseRegexpOperation {
    static inline void operation(common::ku_string_t& left, common::ku_string_t& right,
        uint8_t& result) {
        result = RE2::PartialMatch(left.getAsString(), parseCypherPattern(right.getAsString()));
    }
};

} // namespace function
} // namespace lbug
