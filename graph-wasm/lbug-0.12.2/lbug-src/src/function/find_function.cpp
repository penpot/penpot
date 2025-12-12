#include "function/string/functions/find_function.h"

#include <cstring>

using namespace lbug::common;

namespace lbug {
namespace function {

template<class UNSIGNED>
int64_t Find::unalignedNeedleSizeFind(const uint8_t* haystack, uint32_t haystackLen,
    const uint8_t* needle, uint32_t needleLen, uint32_t firstMatchCharOffset) {
    if (needleLen > haystackLen) {
        return -1;
    }
    // We perform unsigned integer comparisons to check for equality of the entire needle in a
    // single comparison. This implementation is inspired by the memmem implementation of
    // freebsd.
    UNSIGNED needleEntry = 0;
    UNSIGNED haystackEntry = 0;
    const UNSIGNED start = (sizeof(UNSIGNED) * 8) - 8;
    const UNSIGNED shift = (sizeof(UNSIGNED) - needleLen) * 8;
    for (auto i = 0u; i < needleLen; i++) {
        needleEntry |= UNSIGNED(needle[i]) << UNSIGNED(start - i * 8);
        haystackEntry |= UNSIGNED(haystack[i]) << UNSIGNED(start - i * 8);
    }
    for (auto offset = needleLen; offset < haystackLen; offset++) {
        if (haystackEntry == needleEntry) {
            return firstMatchCharOffset + offset - needleLen;
        }
        // We adjust the haystack entry by
        // (1) removing the left-most character (shift by 8)
        // (2) adding the next character (bitwise or, with potential shift)
        // this shift is only necessary if the needle size is not aligned with the unsigned
        // integer size (e.g. needle size 3, unsigned integer size 4, we need to shift by 1).
        haystackEntry = (haystackEntry << 8) | ((UNSIGNED(haystack[offset])) << shift);
    }
    if (haystackEntry == needleEntry) {
        return firstMatchCharOffset + haystackLen - needleLen;
    }
    return -1;
}

template<class UNSIGNED>
int64_t Find::alignedNeedleSizeFind(const uint8_t* haystack, uint32_t haystackLen,
    const uint8_t* needle, uint32_t firstMatchCharOffset) {
    if (sizeof(UNSIGNED) > haystackLen) {
        return -1;
    }
    auto needleVal = *((UNSIGNED*)needle);
    for (auto offset = 0u; offset <= haystackLen - sizeof(UNSIGNED); offset++) {
        auto haystackVal = *((UNSIGNED*)(haystack + offset));
        if (needleVal == haystackVal) {
            return firstMatchCharOffset + offset;
        }
    }
    return -1;
}

int64_t Find::genericFind(const uint8_t* haystack, uint32_t haystackLen, const uint8_t* needle,
    uint32_t needLen, uint32_t firstMatchCharOffset) {
    if (needLen > haystackLen) {
        return -1;
    }
    // This implementation is inspired by Raphael Javaux's faststrstr
    // (https://github.com/RaphaelJ/fast_strstr) generic contains; note that we can't use strstr
    // because we don't have null-terminated strings anymore we keep track of a shifting window
    // sum of all characters with window size equal to needle_size this shifting sum is used to
    // avoid calling into memcmp; we only need to call into memcmp when the window sum is equal
    // to the needle sum when that happens, the characters are potentially the same and we call
    // into memcmp to check if they are.
    auto sumsDiff = 0u;
    for (auto i = 0u; i < needLen; i++) {
        sumsDiff += haystack[i];
        sumsDiff -= needle[i];
    }
    auto offset = 0u;
    while (true) {
        if (sumsDiff == 0 && haystack[offset] == needle[0]) {
            if (memcmp(haystack + offset, needle, needLen) == 0) {
                return firstMatchCharOffset + offset;
            }
        }
        if (offset >= haystackLen - needLen) {
            return -1;
        }
        sumsDiff -= haystack[offset];
        sumsDiff += haystack[offset + needLen];
        offset++;
    }
}

// Returns the position of the first occurrence of needle in the haystack. If haystack doesn't
// contain needle, it returns -1.
int64_t Find::find(const uint8_t* haystack, uint32_t haystackLen, const uint8_t* needle,
    uint32_t needleLen) {
    auto firstMatchCharPos = (uint8_t*)memchr(haystack, needle[0], haystackLen);
    if (firstMatchCharPos == nullptr) {
        return -1;
    }
    auto firstMatchCharOffset = firstMatchCharPos - haystack;
    auto numCharsToMatch = haystackLen - firstMatchCharOffset;
    switch (needleLen) {
    case 1:
        return firstMatchCharOffset;
    case 2:
        return alignedNeedleSizeFind<uint16_t>(firstMatchCharPos, numCharsToMatch, needle,
            firstMatchCharOffset);
    case 3:
        return unalignedNeedleSizeFind<uint32_t>(firstMatchCharPos, numCharsToMatch, needle, 3,
            firstMatchCharOffset);
    case 4:
        return alignedNeedleSizeFind<uint32_t>(firstMatchCharPos, numCharsToMatch, needle,
            firstMatchCharOffset);
    case 5:
    case 6:
    case 7:
        return unalignedNeedleSizeFind<uint64_t>(firstMatchCharPos, numCharsToMatch, needle,
            needleLen, firstMatchCharOffset);
    case 8:
        return alignedNeedleSizeFind<uint64_t>(firstMatchCharPos, numCharsToMatch, needle,
            firstMatchCharOffset);
    default:
        return genericFind(firstMatchCharPos, numCharsToMatch, needle, needleLen,
            firstMatchCharOffset);
    }
}

} // namespace function
} // namespace lbug
