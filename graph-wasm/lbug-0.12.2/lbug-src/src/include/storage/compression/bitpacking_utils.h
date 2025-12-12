// Adapted from
// https://github.com/duckdb/duckdb/blob/main/src/include/duckdb/common/bitpacking.hpp

#pragma once

#include "storage/compression/compression.h"

namespace lbug::storage {

template<IntegerBitpackingType UncompressedType>
struct BitpackingUtils {
    using CompressedType =
        std::conditional_t<sizeof(UncompressedType) >= sizeof(uint32_t), uint32_t, uint8_t>;
    static constexpr size_t sizeOfCompressedTypeBits = sizeof(CompressedType) * 8;

    static void unpackSingle(const uint8_t* __restrict src, UncompressedType* __restrict dst,
        uint16_t bitWidth, size_t srcOffset);

    static void packSingle(const UncompressedType src, uint8_t* __restrict dstCursor,
        uint16_t bitWidth, size_t dstOffset);
};

} // namespace lbug::storage
