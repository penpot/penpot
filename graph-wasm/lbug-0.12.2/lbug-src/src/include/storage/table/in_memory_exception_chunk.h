#pragma once

#include "storage/compression/float_compression.h"
#include "storage/table/column_reader_writer.h"

namespace lbug {
namespace transaction {
class Transaction;
}
namespace storage {

class Column;
class MemoryManager;
class ColumnReadWriter;
struct ColumnChunkMetadata;
struct ChunkState;

// In memory representation of ALP exception chunk
// NOTE: read and write operations on this chunk cannot both be performed on this
// Additionally, each exceptionIdx can be updated at most once before finalizing
// After such operations are performed, you must call finalizeAndFlushToDisk() before reading again
template<std::floating_point T>
class LBUG_API InMemoryExceptionChunk {
public:
    InMemoryExceptionChunk(const SegmentState& state, FileHandle* dataFH,
        MemoryManager* memoryManager, ShadowFile* shadowFile);
    ~InMemoryExceptionChunk();

    void finalizeAndFlushToDisk(SegmentState& state);

    void addException(EncodeException<T> exception);

    void removeExceptionAt(size_t exceptionIdx);

    EncodeException<T> getExceptionAt(size_t exceptionIdx) const;

    common::offset_t findFirstExceptionAtOrPastOffset(common::offset_t offsetInChunk) const;

    size_t getExceptionCount() const;

    void writeException(EncodeException<T> exception, size_t exceptionIdx);

private:
    static PageCursor getExceptionPageCursor(const ColumnChunkMetadata& metadata,
        PageCursor pageBaseCursor, size_t exceptionCapacity);

    void finalize(SegmentState& state);

    static constexpr common::PhysicalTypeID physicalType =
        std::is_same_v<T, float> ? common::PhysicalTypeID::ALP_EXCEPTION_FLOAT :
                                   common::PhysicalTypeID::ALP_EXCEPTION_DOUBLE;

    size_t exceptionCount;
    size_t finalizedExceptionCount;
    size_t exceptionCapacity;

    common::NullMask emptyMask;

    std::unique_ptr<Column> column;
    std::unique_ptr<ColumnChunkData> chunkData;
    std::unique_ptr<SegmentState> chunkState;
};

} // namespace storage
} // namespace lbug
