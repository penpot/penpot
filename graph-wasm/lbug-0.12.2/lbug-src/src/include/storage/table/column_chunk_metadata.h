#pragma once

#include "common/types/types.h"
#include "storage/compression/compression.h"
#include "storage/page_range.h"

namespace lbug::storage {
struct ColumnChunkMetadata {
    PageRange pageRange;
    uint64_t numValues;
    CompressionMetadata compMeta;

    common::page_idx_t getStartPageIdx() const { return pageRange.startPageIdx; }
    common::page_idx_t getNumPages() const { return pageRange.numPages; }

    // Returns the number of pages used to store data
    // In the case of ALP compression, this does not include the number of pages used to store
    // exceptions
    common::page_idx_t getNumDataPages(common::PhysicalTypeID dataType) const;

    void serialize(common::Serializer& serializer) const;
    static ColumnChunkMetadata deserialize(common::Deserializer& deserializer);

    // TODO(Guodong): Delete copy constructor.
    ColumnChunkMetadata()
        : pageRange(common::INVALID_PAGE_IDX, 0), numValues{0},
          compMeta(StorageValue(), StorageValue(), CompressionType::CONSTANT) {}
    ColumnChunkMetadata(common::page_idx_t pageIdx, common::page_idx_t numPages, uint64_t numValues,
        const CompressionMetadata& compMeta)
        : pageRange(pageIdx, numPages), numValues(numValues), compMeta(compMeta) {}
};

class GetCompressionMetadata {
    std::shared_ptr<CompressionAlg> alg;
    const common::LogicalType& dataType;

public:
    GetCompressionMetadata(std::shared_ptr<CompressionAlg> alg, const common::LogicalType& dataType)
        : alg{std::move(alg)}, dataType{dataType} {}

    GetCompressionMetadata(const GetCompressionMetadata& other) = default;

    ColumnChunkMetadata operator()(std::span<const uint8_t> buffer, uint64_t numValues,
        StorageValue min, StorageValue max) const;
};

class GetBitpackingMetadata {
    std::shared_ptr<CompressionAlg> alg;
    const common::LogicalType& dataType;

public:
    GetBitpackingMetadata(std::shared_ptr<CompressionAlg> alg, const common::LogicalType& dataType)
        : alg{std::move(alg)}, dataType{dataType} {}

    GetBitpackingMetadata(const GetBitpackingMetadata& other) = default;

    ColumnChunkMetadata operator()(std::span<const uint8_t> buffer, uint64_t numValues,
        StorageValue min, StorageValue max);
};

template<std::floating_point T>
class GetFloatCompressionMetadata {
    std::shared_ptr<CompressionAlg> alg;
    const common::LogicalType& dataType;

public:
    GetFloatCompressionMetadata(std::shared_ptr<CompressionAlg> alg,
        const common::LogicalType& dataType)
        : alg{std::move(alg)}, dataType{dataType} {}

    GetFloatCompressionMetadata(const GetFloatCompressionMetadata& other) = default;

    ColumnChunkMetadata operator()(std::span<const uint8_t> buffer, uint64_t numValues,
        StorageValue min, StorageValue max);
};

ColumnChunkMetadata uncompressedGetMetadata(common::PhysicalTypeID dataType, uint64_t numValues,
    StorageValue min, StorageValue max);

ColumnChunkMetadata booleanGetMetadata(uint64_t numValues, StorageValue min, StorageValue max);
} // namespace lbug::storage
