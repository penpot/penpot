#pragma once

#include "common/types/ku_string.h"
#include "ltrim_function.h"
#include "rtrim_function.h"

namespace lbug {
namespace function {

struct Trim : BaseStrOperation {
public:
    static inline void operation(common::ku_string_t& input, common::ku_string_t& result,
        common::ValueVector& resultValueVector) {
        BaseStrOperation::operation(input, result, resultValueVector, trim);
    }

private:
    static uint32_t trim(char* data, uint32_t len) {
        return Rtrim::rtrim(data, Ltrim::ltrim(data, len));
    }
};

} // namespace function
} // namespace lbug
