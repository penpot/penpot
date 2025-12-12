// packSingle/unpackSingle are adapted from
// https://github.com/duckdb/duckdb/blob/main/src/storage/compression/bitpacking_hugeint.cpp

#include "storage/compression/bitpacking_utils.h"

#include "common/utils.h"

namespace lbug::storage {
namespace {
template<size_t compressed_field, std::integral CompressedType,
    IntegerBitpackingType UncompressedType>
void unpackSingleField(const CompressedType* __restrict in, UncompressedType* __restrict out,
    uint16_t delta, uint16_t shiftRight) {
    static constexpr size_t compressedFieldSizeBits = sizeof(CompressedType) * 8;

    if constexpr (compressed_field == 0) {
        *out = static_cast<UncompressedType>(in[0]) >> shiftRight;
    } else {
        unpackSingleField<compressed_field - 1>(in, out, delta, shiftRight);
        KU_ASSERT(
            sizeof(UncompressedType) * 8 > compressed_field * compressedFieldSizeBits - shiftRight);
        *out |= static_cast<UncompressedType>(in[compressed_field])
                << (compressed_field * compressedFieldSizeBits - shiftRight);
    }
}

template<std::integral CompressedType, IntegerBitpackingType UncompressedType>
void unpackSingleValueInPlace(const CompressedType* __restrict in, UncompressedType* __restrict out,
    uint16_t delta, uint16_t shiftRight) {
    static_assert(sizeof(UncompressedType) <= 4 * sizeof(CompressedType));

    static constexpr size_t compressedFieldSizeBits = sizeof(CompressedType) * 8;

    if (delta + shiftRight <= compressedFieldSizeBits) {
        unpackSingleField<0>(in, out, delta, shiftRight);
    } else if (delta + shiftRight > compressedFieldSizeBits &&
               delta + shiftRight <= 2 * compressedFieldSizeBits) {
        unpackSingleField<1>(in, out, delta, shiftRight);
    } else if (delta + shiftRight > 2 * compressedFieldSizeBits &&
               delta + shiftRight <= 3 * compressedFieldSizeBits) {
        unpackSingleField<2>(in, out, delta, shiftRight);
    } else if (delta + shiftRight > 3 * compressedFieldSizeBits &&
               delta + shiftRight <= 4 * compressedFieldSizeBits) {
        unpackSingleField<3>(in, out, delta, shiftRight);
    } else if (delta + shiftRight > 4 * compressedFieldSizeBits) {
        unpackSingleField<4>(in, out, delta, shiftRight);
    }

    // we previously copy over the entire most significant field
    // zero out the bits that are not actually part of the compressed value
    *out &= common::BitmaskUtils::all1sMaskForLeastSignificantBits<UncompressedType>(delta);
}

template<bool shiftRight, std::integral CompressedType, IntegerBitpackingType UncompressedType>
void setValueForBitsMatchingMask(CompressedType& out, UncompressedType unshiftedValue,
    UncompressedType unshiftedMask, size_t shift) {
    CompressedType valueToSet = 0;
    CompressedType mask = 0;
    if constexpr (shiftRight) {
        valueToSet = static_cast<CompressedType>((unshiftedValue & unshiftedMask) >> shift);
        mask = static_cast<CompressedType>(unshiftedMask >> shift);
    } else {
        valueToSet = static_cast<CompressedType>((unshiftedValue & unshiftedMask) << shift);
        mask = static_cast<CompressedType>(unshiftedMask << shift);
    }
    const CompressedType bitsToSet = valueToSet & mask;
    const CompressedType bitsToClear = ~mask | valueToSet;
    out = (out | bitsToSet) & bitsToClear;
}

template<size_t compressed_field, std::integral CompressedType,
    IntegerBitpackingType UncompressedType>
void packSingleField(const UncompressedType in, CompressedType* __restrict out, uint16_t delta,
    uint16_t shiftLeft, UncompressedType mask) {
    static constexpr size_t compressedFieldSizeBits = sizeof(CompressedType) * 8;

    if constexpr (compressed_field == 0) {
        setValueForBitsMatchingMask<false>(out[0], in, mask, shiftLeft);
    } else {
        packSingleField<compressed_field - 1>(in, out, delta, shiftLeft, mask);
        KU_ASSERT(
            sizeof(UncompressedType) * 8 > compressed_field * compressedFieldSizeBits - shiftLeft);

        setValueForBitsMatchingMask<true>(out[compressed_field], in, mask,
            (compressed_field * compressedFieldSizeBits - shiftLeft));
    }
}

template<std::integral CompressedType, IntegerBitpackingType UncompressedType>
void packSingleImpl(const UncompressedType in, CompressedType* __restrict out, uint16_t delta,
    uint16_t shiftLeft, UncompressedType mask) {
    static_assert(sizeof(UncompressedType) <= 4 * sizeof(CompressedType));

    static constexpr size_t compressedFieldSizeBits = sizeof(CompressedType) * 8;

    if (delta + shiftLeft <= compressedFieldSizeBits) {
        packSingleField<0>(in, out, delta, shiftLeft, mask);
    } else if (delta + shiftLeft > compressedFieldSizeBits &&
               delta + shiftLeft <= 2 * compressedFieldSizeBits) {
        packSingleField<1>(in, out, delta, shiftLeft, mask);
    } else if (delta + shiftLeft > 2 * compressedFieldSizeBits &&
               delta + shiftLeft <= 3 * compressedFieldSizeBits) {
        packSingleField<2>(in, out, delta, shiftLeft, mask);
    } else if (delta + shiftLeft > 3 * compressedFieldSizeBits &&
               delta + shiftLeft <= 4 * compressedFieldSizeBits) {
        packSingleField<3>(in, out, delta, shiftLeft, mask);
    } else if (delta + shiftLeft > 4 * compressedFieldSizeBits) {
        packSingleField<4>(in, out, delta, shiftLeft, mask);
    }
}
} // namespace

template<IntegerBitpackingType UncompressedType>
void BitpackingUtils<UncompressedType>::unpackSingle(const uint8_t* __restrict srcCursor,
    UncompressedType* __restrict dst, uint16_t bitWidth, size_t srcOffset) {
    const size_t srcBufferOffset = srcOffset * bitWidth / sizeOfCompressedTypeBits;
    const size_t shiftRight = srcOffset * bitWidth % sizeOfCompressedTypeBits;

    const auto* castedSrcCursor =
        reinterpret_cast<const CompressedType*>(srcCursor) + srcBufferOffset;
    unpackSingleValueInPlace(castedSrcCursor, dst, bitWidth, shiftRight);
}

template<IntegerBitpackingType UncompressedType>
void BitpackingUtils<UncompressedType>::packSingle(const UncompressedType src,
    uint8_t* __restrict dstBuffer, uint16_t bitWidth, size_t dstOffset) {
    const size_t dstBufferOffset = dstOffset * bitWidth / sizeOfCompressedTypeBits;
    const size_t shiftLeft = dstOffset * bitWidth % sizeOfCompressedTypeBits;

    auto* castedDstBuffer = reinterpret_cast<CompressedType*>(dstBuffer) + dstBufferOffset;
    packSingleImpl(src, castedDstBuffer, bitWidth, shiftLeft,
        common::BitmaskUtils::all1sMaskForLeastSignificantBits<UncompressedType>(bitWidth));
}

template struct BitpackingUtils<common::int128_t>;
template struct BitpackingUtils<uint64_t>;
template struct BitpackingUtils<uint32_t>;
template struct BitpackingUtils<uint16_t>;
template struct BitpackingUtils<uint8_t>;
} // namespace lbug::storage
