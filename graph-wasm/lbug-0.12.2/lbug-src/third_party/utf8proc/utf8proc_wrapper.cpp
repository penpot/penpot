#include "include/utf8proc_wrapper.h"

#include "include/utf8proc.h"

using namespace std;

namespace lbug {
namespace utf8proc {

static void assignInvalidUtf8Reason(UnicodeInvalidReason* invalidReason, size_t* invalidPos,
    size_t pos, UnicodeInvalidReason reason) {
    if (invalidReason) {
        *invalidReason = reason;
    }
    if (invalidPos) {
        *invalidPos = pos;
    }
}

template<const int nextraBytes, const int mask>
static inline UnicodeType UTF8ExtraByteLoop(const int firstPosSeq, int utf8char, size_t& i,
    const char* s, const size_t len, UnicodeInvalidReason* invalidReason, size_t* invalidPos) {
    if ((len - i) < (nextraBytes + 1)) {
        assignInvalidUtf8Reason(
            invalidReason, invalidPos, firstPosSeq, UnicodeInvalidReason::BYTE_MISMATCH);
        return UnicodeType::INVALID;
    }
    for (size_t j = 0; j < nextraBytes; j++) {
        int c = (int)s[++i];
        if ((c & 0xC0) != 0x80) {
            assignInvalidUtf8Reason(
                invalidReason, invalidPos, i, UnicodeInvalidReason::BYTE_MISMATCH);
            return UnicodeType::INVALID;
        }
        utf8char = (utf8char << 6) | (c & 0x3F);
    }
    if ((utf8char & mask) == 0) {
        assignInvalidUtf8Reason(
            invalidReason, invalidPos, firstPosSeq, UnicodeInvalidReason::INVALID_UNICODE);
        return UnicodeType::INVALID;
    }
    if (utf8char > 0x10FFFF) {
        assignInvalidUtf8Reason(
            invalidReason, invalidPos, firstPosSeq, UnicodeInvalidReason::INVALID_UNICODE);
        return UnicodeType::INVALID;
    }
    if ((utf8char & 0x1FFF800) == 0xD800) {
        assignInvalidUtf8Reason(
            invalidReason, invalidPos, firstPosSeq, UnicodeInvalidReason::INVALID_UNICODE);
        return UnicodeType::INVALID;
    }
    return UnicodeType::UNICODE;
}

UnicodeType Utf8Proc::analyze(
    const char* s, size_t len, UnicodeInvalidReason* invalidReason, size_t* invalidPos) {
    UnicodeType type = UnicodeType::ASCII;

    for (size_t i = 0; i < len; i++) {
        int c = (int)s[i];

        if ((c & 0x80) == 0) {
            continue;
        } else {
            int firstPosSeq = i;

            if ((c & 0xE0) == 0xC0) {
                int utf8char = c & 0x1F;
                type = UTF8ExtraByteLoop<1, 0x000780>(
                    firstPosSeq, utf8char, i, s, len, invalidReason, invalidPos);
            } else if ((c & 0xF0) == 0xE0) {
                int utf8char = c & 0x0F;
                type = UTF8ExtraByteLoop<2, 0x00F800>(
                    firstPosSeq, utf8char, i, s, len, invalidReason, invalidPos);
            } else if ((c & 0xF8) == 0xF0) {
                int utf8char = c & 0x07;
                type = UTF8ExtraByteLoop<3, 0x1F0000>(
                    firstPosSeq, utf8char, i, s, len, invalidReason, invalidPos);
            } else {
                assignInvalidUtf8Reason(
                    invalidReason, invalidPos, i, UnicodeInvalidReason::BYTE_MISMATCH);
                return UnicodeType::INVALID;
            }
            if (type == UnicodeType::INVALID) {
                return type;
            }
        }
    }
    return type;
}

char* Utf8Proc::normalize(const char* s, size_t len) {
    assert(s);
    assert(Utf8Proc::analyze(s, len) != UnicodeType::INVALID);
    return (char*)utf8proc_NFC((const utf8proc_uint8_t*)s, len);
}

bool Utf8Proc::isValid(const char* s, size_t len) {
    return Utf8Proc::analyze(s, len) != UnicodeType::INVALID;
}

size_t Utf8Proc::previousGraphemeCluster(const char* s, size_t len, size_t charPos) {
    if (!Utf8Proc::isValid(s, len)) {
        return charPos - 1;
    }
    size_t currentPos = 0;
    while (true) {
        size_t newPos = utf8proc_next_grapheme(s, len, currentPos);
        if (newPos <= currentPos || newPos >= charPos) {
            return currentPos;
        }
        currentPos = newPos;
    }
}

int32_t Utf8Proc::utf8ToCodepoint(const char* c, int& size) {
    return utf8proc_codepoint(c, size);
}

uint32_t Utf8Proc::renderWidth(const char* s, size_t pos) {
    int size;
    auto codepoint = lbug::utf8proc::utf8proc_codepoint(s + pos, size);
    auto properties = lbug::utf8proc::utf8proc_get_property(codepoint);
    return properties->charwidth;
}

int Utf8Proc::codepointLength(int cp) {
    return utf8proc_codepoint_length(cp);
}

bool Utf8Proc::codepointToUtf8(int cp, int &sz, char *c) {
    return utf8proc_codepoint_to_utf8(cp, sz, c);
}

} // namespace utf8proc
} // namespace lbug
