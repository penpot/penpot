#pragma once

#include "common/types/ku_string.h"

namespace lbug {
namespace function {

// The string find algorithm is copied from duckdb. Source code:
// https://github.com/duckdb/duckdb/blob/master/src/function/scalar/string/contains.cpp

struct Find {
    static inline void operation(common::ku_string_t& left, common::ku_string_t& right,
        int64_t& result) {
        if (right.len == 0) {
            result = 1;
        } else if (right.len > left.len) {
            result = 0;
        }
        result = Find::find(left.getData(), left.len, right.getData(), right.len) + 1;
    }

private:
    template<class UNSIGNED>
    static int64_t unalignedNeedleSizeFind(const uint8_t* haystack, uint32_t haystackLen,
        const uint8_t* needle, uint32_t needleLen, uint32_t firstMatchCharOffset);

    template<class UNSIGNED>
    static int64_t alignedNeedleSizeFind(const uint8_t* haystack, uint32_t haystackLen,
        const uint8_t* needle, uint32_t firstMatchCharOffset);

    static int64_t genericFind(const uint8_t* haystack, uint32_t haystackLen, const uint8_t* needle,
        uint32_t needLen, uint32_t firstMatchCharOffset);

    // Returns the position of the first occurrence of needle in the haystack. If haystack doesn't
    // contain needle, it returns -1.
    static int64_t find(const uint8_t* haystack, uint32_t haystackLen, const uint8_t* needle,
        uint32_t needleLen);
};

} // namespace function
} // namespace lbug
