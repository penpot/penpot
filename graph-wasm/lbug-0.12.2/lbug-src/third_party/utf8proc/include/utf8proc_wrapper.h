#pragma once

#include <cassert>
#include <cstdint>
#include <cstring>
#include <string>

namespace lbug {
namespace utf8proc {

enum class UnicodeType { INVALID, ASCII, UNICODE };
enum class UnicodeInvalidReason { BYTE_MISMATCH, INVALID_UNICODE };

class Utf8Proc {
public:
    static UnicodeType analyze(const char* s, size_t len,
        UnicodeInvalidReason* invalidReason = nullptr, size_t* invalidPos = nullptr);

    static char* normalize(const char* s, size_t len);

    static bool isValid(const char* s, size_t len);

    static size_t previousGraphemeCluster(const char* s, size_t len, size_t charPos);

    static int32_t utf8ToCodepoint(const char* c, int& size);

    static uint32_t renderWidth(const char* s, size_t pos);

    static int codepointLength(int cp);

    static bool codepointToUtf8(int cp, int &sz, char *c);
};

} // namespace utf8proc
} // namespace lbug
