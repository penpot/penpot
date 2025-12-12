#pragma once

#include "common/exception/runtime.h"
#include "common/types/blob.h"
#include "common/vector/value_vector.h"
#include "utf8proc_wrapper.h"

namespace lbug {
namespace function {

struct Decode {
    static inline void operation(common::blob_t& input, common::ku_string_t& result,
        common::ValueVector& resultVector) {
        if (utf8proc::Utf8Proc::analyze(reinterpret_cast<const char*>(input.value.getData()),
                input.value.len) == utf8proc::UnicodeType::INVALID) {
            throw common::RuntimeException(
                "Failure in decode: could not convert blob to UTF8 string, "
                "the blob contained invalid UTF8 characters");
        }
        common::StringVector::addString(&resultVector, result, input.value);
    }
};

} // namespace function
} // namespace lbug
