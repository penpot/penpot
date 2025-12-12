#pragma once

#include "common/exception/runtime.h"
#include "common/types/ku_string.h"
#include "common/vector/value_vector.h"
#include "function/string/functions/base_regexp_function.h"
#include "re2.h"

namespace lbug {
namespace function {

struct RegexpExtract : BaseRegexpOperation {
    static inline void operation(common::ku_string_t& value, common::ku_string_t& pattern,
        std::int64_t& group, common::ku_string_t& result, common::ValueVector& resultValueVector) {
        regexExtract(value.getAsString(), pattern.getAsString(), group, result, resultValueVector);
    }

    static inline void operation(common::ku_string_t& value, common::ku_string_t& pattern,
        common::ku_string_t& result, common::ValueVector& resultValueVector) {
        int64_t defaultGroup = 0;
        regexExtract(value.getAsString(), pattern.getAsString(), defaultGroup, result,
            resultValueVector);
    }

    static void regexExtract(const std::string& input, const std::string& pattern,
        std::int64_t& group, common::ku_string_t& result, common::ValueVector& resultValueVector) {
        RE2 regex(parseCypherPattern(pattern));
        auto submatchCount = regex.NumberOfCapturingGroups() + 1;
        if (group >= submatchCount) {
            throw common::RuntimeException("Regex match group index is out of range");
        }

        std::vector<regex::StringPiece> targetSubMatches;
        targetSubMatches.resize(submatchCount);

        if (!regex.Match(regex::StringPiece(input), 0, input.length(), RE2::UNANCHORED,
                targetSubMatches.data(), submatchCount)) {
            return;
        }

        copyToLbugString(targetSubMatches[group].ToString(), result, resultValueVector);
    }
};

} // namespace function
} // namespace lbug
