#pragma once

#include <type_traits>

#include "storage/compression/compression.h"
#include <concepts>

namespace lbug {
namespace common {
class ValueVector;
class NullMask;
} // namespace common

namespace storage {
class ColumnChunkData;

struct PageCursor;

template<std::floating_point T>
struct EncodeException {
    T value;
    uint32_t posInChunk;

    static constexpr size_t sizeInBytes() { return sizeof(value) + sizeof(posInChunk); }

    static size_t numPagesFromExceptions(size_t exceptionCount);

    static size_t exceptionBytesPerPage();

    bool operator<(const EncodeException<T>& o) const;
};

template<std::floating_point T>
struct EncodeExceptionView {
    // Used to access ALP exceptions that are stored in buffers
    // We don't use the EncodeException struct directly since we don't want to copy struct padding
    explicit EncodeExceptionView(std::byte* val) { bytes = val; }

    EncodeException<T> getValue(common::offset_t elementOffset = 0) const;
    void setValue(EncodeException<T> exception, common::offset_t elementOffset = 0);
    std::byte* bytes;
};

template<std::floating_point T>
class FloatCompression final : public CompressionAlg {
public:
    using EncodedType = std::conditional_t<std::is_same_v<T, double>, int64_t, int32_t>;
    static constexpr size_t MAX_EXCEPTION_FACTOR = 4;

public:
    FloatCompression();

    void setValuesFromUncompressed(const uint8_t* srcBuffer, common::offset_t srcOffset,
        uint8_t* dstBuffer, common::offset_t dstOffset, common::offset_t numValues,
        const CompressionMetadata& metadata, const common::NullMask* nullMask) const override;

    static uint64_t numValues(uint64_t dataSize, const CompressionMetadata& metadata);

    // this is included to satisfy the CompressionAlg interface but we don't actually use it
    uint64_t compressNextPage(const uint8_t*& srcBuffer, uint64_t numValuesRemaining,
        uint8_t* dstBuffer, uint64_t dstBufferSize,
        const CompressionMetadata& metadata) const override;

    uint64_t compressNextPageWithExceptions(const uint8_t*& srcBuffer, uint64_t srcOffset,
        uint64_t numValuesRemaining, uint8_t* dstBuffer, uint64_t dstBufferSize,
        EncodeExceptionView<T> exceptionBuffer, uint64_t exceptionBufferSize,
        uint64_t& exceptionCount, const CompressionMetadata& metadata) const;

    // does not patch exceptions (this is handled by the column reader)
    void decompressFromPage(const uint8_t* srcBuffer, uint64_t srcOffset, uint8_t* dstBuffer,
        uint64_t dstOffset, uint64_t numValues, const CompressionMetadata& metadata) const override;

    static bool canUpdateInPlace(std::span<const T> value, const CompressionMetadata& metadata,
        InPlaceUpdateLocalState& localUpdateState,
        const std::optional<common::NullMask>& nullMask = std::nullopt,
        uint64_t nullMaskOffset = 0);

    CompressionType getCompressionType() const override { return CompressionType::ALP; }

    static BitpackInfo<EncodedType> getBitpackInfo(const CompressionMetadata& metadata);

    // Returns number of pages for storing bitpacked ALP values (excluding pages reserved for
    // exceptions)
    static common::page_idx_t getNumDataPages(common::page_idx_t numTotalPages,
        const CompressionMetadata& compMeta);

private:
    const CompressionAlg& getEncodedFloatBitpacker(const CompressionMetadata& metadata) const;

    ConstantCompression constantEncodedFloatBitpacker;
    IntegerBitpacking<EncodedType> encodedFloatBitpacker;
};

} // namespace storage
} // namespace lbug
