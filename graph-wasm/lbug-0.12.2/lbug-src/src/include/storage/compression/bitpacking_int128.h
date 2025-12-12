// Adapted from
// https://github.com/duckdb/duckdb/blob/main/src/include/duckdb/common/bitpacking.hpp

#pragma once

#include "common/types/int128_t.h"

namespace lbug::storage {

struct Int128Packer {
    static void pack(const common::int128_t* __restrict in, uint32_t* __restrict out,
        uint8_t width);
    static void unpack(const uint32_t* __restrict in, common::int128_t* __restrict out,
        uint8_t width);
};

} // namespace lbug::storage
