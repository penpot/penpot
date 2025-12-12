#pragma once

#include <string>

#include "mbedtls/sha256.h"

namespace lbug {
namespace common {

class SHA256 {
public:
    static constexpr size_t SHA256_HASH_LENGTH_BYTES = 32;
    static constexpr size_t SHA256_HASH_LENGTH_TEXT = 64;

public:
    SHA256();
    ~SHA256();
    void addString(const std::string& str);
    void finishSHA256(char* out);
    static void toBase16(const char* in, char* out, size_t len);

private:
    typedef mbedtls_sha256_context SHA256Context;

    SHA256Context shaContext;
};

} // namespace common
} // namespace lbug
