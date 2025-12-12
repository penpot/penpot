#pragma once

#include "common/assert.h"
#include "common/types/ku_string.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct PadOperation {
public:
    static inline void operation(common::ku_string_t& src, int64_t count,
        common::ku_string_t& characterToPad, common::ku_string_t& result,
        common::ValueVector& resultValueVector,
        void (*padOperation)(common::ku_string_t& result, common::ku_string_t& src,
            common::ku_string_t& characterToPad)) {
        if (count <= 0) {
            result.set("", 0);
            return;
        }
        KU_ASSERT(characterToPad.len == 1);
        padOperation(result, src, characterToPad);
        common::StringVector::addString(&resultValueVector, result, (const char*)result.getData(),
            count);
    }
};

} // namespace function
} // namespace lbug
