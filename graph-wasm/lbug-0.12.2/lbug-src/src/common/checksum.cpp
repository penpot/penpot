/**
 * The implementation for checksumming is taken from DuckDB:
 * https://github.com/duckdb/duckdb/blob/v1.3-ossivalis/src/common/checksum.cpp
 * https://github.com/duckdb/duckdb/blob/v1.3-ossivalis/LICENSE
 */

#include "common/checksum.h"

#include "common/types/types.h"

namespace lbug::common {

hash_t checksum(uint64_t x) {
    return x * UINT64_C(0xbf58476d1ce4e5b9);
}

// MIT License
// Copyright (c) 2018-2021 Martin Ankerl
// https://github.com/martinus/robin-hood-hashing/blob/3.11.5/LICENSE
hash_t checksumRemainder(void* ptr, size_t len) noexcept {
    static constexpr uint64_t M = UINT64_C(0xc6a4a7935bd1e995);
    static constexpr uint64_t SEED = UINT64_C(0xe17a1465);
    static constexpr unsigned int R = 47;

    auto const* const data64 = static_cast<uint64_t const*>(ptr);
    uint64_t h = SEED ^ (len * M);

    size_t const n_blocks = len / 8;
    for (size_t i = 0; i < n_blocks; ++i) {
        auto k = *reinterpret_cast<const uint64_t*>(data64 + i);

        k *= M;
        k ^= k >> R;
        k *= M;

        h ^= k;
        h *= M;
    }

    auto const* const data8 = reinterpret_cast<uint8_t const*>(data64 + n_blocks);
    switch (len & 7U) {
    case 7:
        h ^= static_cast<uint64_t>(data8[6]) << 48U;
        [[fallthrough]];
    case 6:
        h ^= static_cast<uint64_t>(data8[5]) << 40U;
        [[fallthrough]];
    case 5:
        h ^= static_cast<uint64_t>(data8[4]) << 32U;
        [[fallthrough]];
    case 4:
        h ^= static_cast<uint64_t>(data8[3]) << 24U;
        [[fallthrough]];
    case 3:
        h ^= static_cast<uint64_t>(data8[2]) << 16U;
        [[fallthrough]];
    case 2:
        h ^= static_cast<uint64_t>(data8[1]) << 8U;
        [[fallthrough]];
    case 1:
        h ^= static_cast<uint64_t>(data8[0]);
        h *= M;
        [[fallthrough]];
    default:
        break;
    }
    h ^= h >> R;
    h *= M;
    h ^= h >> R;
    return static_cast<hash_t>(h);
}

uint64_t checksum(uint8_t* buffer, size_t size) {
    uint64_t result = 5381;
    uint64_t* ptr = reinterpret_cast<uint64_t*>(buffer);
    size_t i{};
    // for efficiency, we first checksum uint64_t values
    for (i = 0; i < size / 8; i++) {
        result ^= checksum(ptr[i]);
    }
    if (size - i * 8 > 0) {
        // the remaining 0-7 bytes we hash using a string hash
        result ^= checksumRemainder(buffer + i * 8, size - i * 8);
    }
    return result;
}

} // namespace lbug::common
