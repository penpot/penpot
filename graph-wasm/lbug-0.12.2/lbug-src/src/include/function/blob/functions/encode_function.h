#pragma once

#include "common/types/blob.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace function {

struct Encode {
    static inline void operation(common::ku_string_t& input, common::blob_t& result,
        common::ValueVector& resultVector) {
        common::StringVector::addString(&resultVector, result.value, input);
    }
};

} // namespace function
} // namespace lbug
