#pragma once

#include "storage/compression/compression.h"
#include "storage/table/column_chunk_metadata.h"

namespace lbug::storage {
class FileHandle;

class CompressedFlushBuffer {
    std::shared_ptr<CompressionAlg> alg;
    common::PhysicalTypeID dataType;

public:
    CompressedFlushBuffer(std::shared_ptr<CompressionAlg> alg, common::PhysicalTypeID dataType)
        : alg{std::move(alg)}, dataType{dataType} {}
    CompressedFlushBuffer(std::shared_ptr<CompressionAlg> alg, const common::LogicalType& dataType)
        : CompressedFlushBuffer(std::move(alg), dataType.getPhysicalType()) {}

    CompressedFlushBuffer(const CompressedFlushBuffer& other) = default;

    ColumnChunkMetadata operator()(std::span<const uint8_t> buffer, FileHandle* dataFH,
        const PageRange& entry, const ColumnChunkMetadata& metadata) const;
};

template<std::floating_point T>
class CompressedFloatFlushBuffer {
    std::shared_ptr<CompressionAlg> alg;
    common::PhysicalTypeID dataType;

public:
    CompressedFloatFlushBuffer(std::shared_ptr<CompressionAlg> alg,
        common::PhysicalTypeID dataType);
    CompressedFloatFlushBuffer(std::shared_ptr<CompressionAlg> alg,
        const common::LogicalType& dataType);

    CompressedFloatFlushBuffer(const CompressedFloatFlushBuffer& other) = default;

    ColumnChunkMetadata operator()(std::span<const uint8_t> buffer, FileHandle* dataFH,
        const PageRange& entry, const ColumnChunkMetadata& metadata) const;
};

ColumnChunkMetadata uncompressedFlushBuffer(std::span<const uint8_t> buffer, FileHandle* dataFH,
    const PageRange& entry, const ColumnChunkMetadata& metadata);

} // namespace lbug::storage
