#pragma once

#include <cstring>

#include "common/api.h"
#include "common/types/ku_string.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct Repeat {
public:
    LBUG_API static void operation(common::ku_string_t& left, int64_t& right,
        common::ku_string_t& result, common::ValueVector& resultValueVector);

private:
    static void repeatStr(char* data, const std::string& pattern, uint64_t count) {
        for (auto i = 0u; i < count; i++) {
            memcpy(data + i * pattern.length(), pattern.c_str(), pattern.length());
        }
    }
};

} // namespace function
} // namespace lbug
