#pragma once

#include <cstring>

#include "common/types/ku_string.h"
#include "common/vector/value_vector.h"
#include "utf8proc.h"

namespace lbug {
namespace function {

struct SubStr {
public:
    static inline void operation(common::ku_string_t& src, int64_t start, int64_t len,
        common::ku_string_t& result, common::ValueVector& resultValueVector) {
        std::string srcStr = src.getAsString();
        bool isAscii = true;
        int64_t startPos = start - 1;
        int64_t endPos = std::min(srcStr.size(), (size_t)(startPos + len));
        if (startPos >= endPos || startPos < 0 || startPos >= (int64_t)srcStr.size()) {
            result.len = 0;
            return;
        }
        // 1 character more than length has to be scanned for diatrics case: y + ˘ = ў.
        for (auto i = 0u; i < std::min<int64_t>(srcStr.size(), endPos + 1); i++) {
            // UTF-8 character encountered.
            if (srcStr[i] & 0x80) {
                isAscii = false;
                break;
            }
        }
        if (isAscii) {
            copySubstr(src, start, len, result, resultValueVector, true /* isAscii */);
        } else {
            int64_t characterCount = 0, startBytePos = 0, endBytePos = 0;
            lbug::utf8proc::utf8proc_grapheme_callback(srcStr.c_str(), srcStr.size(),
                [&](int64_t gstart, int64_t /*gend*/) {
                    if (characterCount == startPos) {
                        startBytePos = gstart;
                    } else if (characterCount == endPos) {
                        endBytePos = gstart;
                        return false;
                    }
                    characterCount++;
                    return true;
                });
            if (endBytePos == 0 && len != 0) {
                endBytePos = src.len;
            }
            // In this case, the function gets the EXACT byte location to start copying from.
            copySubstr(src, startBytePos, endBytePos - startBytePos, result, resultValueVector,
                false /* isAscii */);
        }
    }

    static inline void copySubstr(common::ku_string_t& src, int64_t start, int64_t len,
        common::ku_string_t& result, common::ValueVector& resultValueVector, bool isAscii) {
        auto length = std::min(len, src.len - start + 1);
        if (isAscii) {
            // For normal ASCII char case, we get to the proper byte position to copy from by doing
            // a -1 (since it is guaranteed each char is 1 byte).
            common::StringVector::addString(&resultValueVector, result,
                (const char*)(src.getData() + start - 1), length);
        } else {
            // For utf8 char copy, the function gets the exact starting byte position to copy from.
            common::StringVector::addString(&resultValueVector, result,
                (const char*)(src.getData() + start), length);
        }
    }
};

} // namespace function
} // namespace lbug
