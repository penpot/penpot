#pragma once

#include "int128_t.h"

namespace lbug {

namespace regex {
class RE2;
}

namespace common {

class RandomEngine;

// Note: uuid_t is a reserved keyword in MSVC, we have to use ku_uuid_t instead.
struct ku_uuid_t {
    int128_t value;
};

struct UUID {
    static constexpr const uint8_t UUID_STRING_LENGTH = 36;
    static constexpr const char HEX_DIGITS[] = "0123456789abcdef";
    static void byteToHex(char byteVal, char* buf, uint64_t& pos);
    static unsigned char hex2Char(char ch);
    static bool isHex(char ch);
    static bool fromString(std::string str, int128_t& result);

    static int128_t fromString(std::string str);
    static int128_t fromCString(const char* str, uint64_t len);
    static void toString(int128_t input, char* buf);
    static std::string toString(int128_t input);
    static std::string toString(ku_uuid_t val);

    static ku_uuid_t generateRandomUUID(RandomEngine* engine);

    static const regex::RE2& regexPattern();
};

} // namespace common
} // namespace lbug
