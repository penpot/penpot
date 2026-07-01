// Skia 0.97 WASM references std::__hash_memory. Compiled via EMCC_CFLAGS at the
// final emcc link (see _build_env). murmur2 matches libc++ on 32-bit wasm.
#include <cstddef>
#include <cstring>

namespace std {
inline namespace __2 {

static size_t loadword(const void* p) {
    size_t r;
    std::memcpy(&r, p, sizeof(r));
    return r;
}

__attribute__((used)) size_t __hash_memory(const void* key, size_t len) noexcept {
    const size_t m = 0x5bd1e995;
    const size_t r = 24;
    size_t h = len;
    const auto* data = static_cast<const unsigned char*>(key);
    for (; len >= 4; data += 4, len -= 4) {
        size_t k = loadword(data);
        k *= m;
        k ^= k >> r;
        k *= m;
        h *= m;
        h ^= k;
    }
    switch (len) {
    case 3:
        h ^= static_cast<size_t>(data[2] << 16);
        [[fallthrough]];
    case 2:
        h ^= static_cast<size_t>(data[1] << 8);
        [[fallthrough]];
    case 1:
        h ^= data[0];
        h *= m;
        break;
    default:
        break;
    }
    h ^= h >> 13;
    h *= m;
    h ^= h >> 15;
    return h;
}

} // namespace __2
} // namespace std
