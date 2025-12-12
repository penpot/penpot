#pragma once

#include "base_pad_function.h"
#include "common/types/ku_string.h"

namespace lbug {
namespace function {

struct Lpad : BasePadOperation {
public:
    static inline void operation(common::ku_string_t& src, int64_t count,
        common::ku_string_t& characterToPad, common::ku_string_t& result,
        common::ValueVector& resultValueVector) {
        BasePadOperation::operation(src, count, characterToPad, result, resultValueVector,
            lpadOperation);
    }

    static void lpadOperation(common::ku_string_t& src, int64_t count,
        common::ku_string_t& characterToPad, std::string& paddedResult) {
        auto srcPadInfo =
            BasePadOperation::padCountChars(count, (const char*)src.getData(), src.len);
        auto srcData = (const char*)src.getData();
        BasePadOperation::insertPadding(count - srcPadInfo.second, characterToPad, paddedResult);
        paddedResult.insert(paddedResult.end(), srcData, srcData + srcPadInfo.first);
    }
};

} // namespace function
} // namespace lbug
