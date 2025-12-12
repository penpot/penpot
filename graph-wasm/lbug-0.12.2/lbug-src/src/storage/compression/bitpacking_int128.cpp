// Adapted from
// https://github.com/duckdb/duckdb/blob/main/src/storage/compression/bitpacking_hugeint.cpp

#include "storage/compression/bitpacking_int128.h"

#include "storage/compression/bitpacking_utils.h"
#include "storage/compression/compression.h"

namespace lbug::storage {

//===--------------------------------------------------------------------===//
// Unpacking
//===--------------------------------------------------------------------===//

static void unpackLast(const uint32_t* __restrict in, common::int128_t* __restrict out,
    uint16_t delta) {
    const uint8_t LAST_IDX = 31;
    const uint16_t SHIFT = (delta * 31) % 32;
    out[LAST_IDX] = in[0] >> SHIFT;
    if (delta > 32) {
        out[LAST_IDX] |= static_cast<common::int128_t>(in[1]) << (32 - SHIFT);
    }
    if (delta > 64) {
        out[LAST_IDX] |= static_cast<common::int128_t>(in[2]) << (64 - SHIFT);
    }
    if (delta > 96) {
        out[LAST_IDX] |= static_cast<common::int128_t>(in[3]) << (96 - SHIFT);
    }
}

// Unpacks for specific deltas
static void unpackDelta0(common::int128_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        out[i] = 0;
    }
}

static void unpackDelta32(const uint32_t* __restrict in, common::int128_t* __restrict out) {
    for (uint8_t k = 0; k < 32; ++k) {
        out[k] = static_cast<common::int128_t>(in[k]);
    }
}

static void unpackDelta64(const uint32_t* __restrict in, common::int128_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        const uint8_t OFFSET = i * 2;
        out[i] = in[OFFSET];
        out[i] |= static_cast<common::int128_t>(in[OFFSET + 1]) << 32;
    }
}

static void unpackDelta96(const uint32_t* __restrict in, common::int128_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        const uint8_t OFFSET = i * 3;
        out[i] = in[OFFSET];
        out[i] |= static_cast<common::int128_t>(in[OFFSET + 1]) << 32;
        out[i] |= static_cast<common::int128_t>(in[OFFSET + 2]) << 64;
    }
}

static void unpackDelta128(const uint32_t* __restrict in, common::int128_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        const uint8_t OFFSET = i * 4;
        out[i] = in[OFFSET];
        out[i] |= static_cast<common::int128_t>(in[OFFSET + 1]) << 32;
        out[i] |= static_cast<common::int128_t>(in[OFFSET + 2]) << 64;
        out[i] |= static_cast<common::int128_t>(in[OFFSET + 3]) << 96;
    }
}

//===--------------------------------------------------------------------===//
// Packing
//===--------------------------------------------------------------------===//

// Packs for specific deltas
static void packDelta32(const common::int128_t* __restrict in, uint32_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        out[i] = static_cast<uint32_t>(in[i]);
    }
}

static void packDelta64(const common::int128_t* __restrict in, uint32_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        const uint8_t OFFSET = 2 * i;
        out[OFFSET] = static_cast<uint32_t>(in[i]);
        out[OFFSET + 1] = static_cast<uint32_t>(in[i] >> 32);
    }
}

static void packDelta96(const common::int128_t* __restrict in, uint32_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        const uint8_t OFFSET = 3 * i;
        out[OFFSET] = static_cast<uint32_t>(in[i]);
        out[OFFSET + 1] = static_cast<uint32_t>(in[i] >> 32);
        out[OFFSET + 2] = static_cast<uint32_t>(in[i] >> 64);
    }
}

static void packDelta128(const common::int128_t* __restrict in, uint32_t* __restrict out) {
    for (uint8_t i = 0; i < 32; ++i) {
        const uint8_t OFFSET = 4 * i;
        out[OFFSET] = static_cast<uint32_t>(in[i]);
        out[OFFSET + 1] = static_cast<uint32_t>(in[i] >> 32);
        out[OFFSET + 2] = static_cast<uint32_t>(in[i] >> 64);
        out[OFFSET + 3] = static_cast<uint32_t>(in[i] >> 96);
    }
}

//===--------------------------------------------------------------------===//
// HugeIntPacker
//===--------------------------------------------------------------------===//

void Int128Packer::pack(const common::int128_t* __restrict in, uint32_t* __restrict out,
    uint8_t width) {
    KU_ASSERT(width <= 128);
    switch (width) {
    case 0:
        break;
    case 32:
        packDelta32(in, out);
        break;
    case 64:
        packDelta64(in, out);
        break;
    case 96:
        packDelta96(in, out);
        break;
    case 128:
        packDelta128(in, out);
        break;
    default:
        for (common::idx_t oindex = 0; oindex < IntegerBitpacking<common::int128_t>::CHUNK_SIZE;
             ++oindex) {
            BitpackingUtils<common::int128_t>::packSingle(in[oindex],
                reinterpret_cast<uint8_t*>(out), width, oindex);
        }
    }
}

void Int128Packer::unpack(const uint32_t* __restrict in, common::int128_t* __restrict out,
    uint8_t width) {
    KU_ASSERT(width <= 128);
    switch (width) {
    case 0:
        unpackDelta0(out);
        break;
    case 32:
        unpackDelta32(in, out);
        break;
    case 64:
        unpackDelta64(in, out);
        break;
    case 96:
        unpackDelta96(in, out);
        break;
    case 128:
        unpackDelta128(in, out);
        break;
    default:
        for (common::idx_t oindex = 0; oindex < IntegerBitpacking<common::int128_t>::CHUNK_SIZE - 1;
             ++oindex) {
            BitpackingUtils<common::int128_t>::unpackSingle(reinterpret_cast<const uint8_t*>(in),
                out + oindex, width, oindex);
        }
        unpackLast(in + +(IntegerBitpacking<common::int128_t>::CHUNK_SIZE - 1) * width /
                            (sizeof(uint32_t) * 8),
            out, width);
    }
}

} // namespace lbug::storage
