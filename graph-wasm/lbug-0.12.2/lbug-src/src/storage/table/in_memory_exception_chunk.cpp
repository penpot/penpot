#include "storage/table/in_memory_exception_chunk.h"

#include <algorithm>

#include "common/utils.h"
#include "storage/buffer_manager/memory_manager.h"
#include "storage/compression/float_compression.h"
#include "storage/storage_utils.h"
#include "storage/table/column.h"
#include "storage/table/column_chunk_data.h"
#include <concepts>

namespace lbug::storage {

using namespace common;
using namespace transaction;

template<std::floating_point T>
using ExceptionInBuffer = std::array<std::byte, EncodeException<T>::sizeInBytes()>;

template<std::floating_point T>
InMemoryExceptionChunk<T>::InMemoryExceptionChunk(const SegmentState& state, FileHandle* dataFH,
    MemoryManager* memoryManager, ShadowFile* shadowFile)
    : exceptionCount(state.metadata.compMeta.floatMetadata()->exceptionCount),
      finalizedExceptionCount(exceptionCount),
      exceptionCapacity(state.metadata.compMeta.floatMetadata()->exceptionCapacity),
      emptyMask(exceptionCapacity),
      column(std::make_unique<Column>("ALPExceptionChunk", physicalType, dataFH, memoryManager,
          shadowFile, false, false /*has nulls*/)) {
    const auto exceptionBaseCursor =
        getExceptionPageCursor(state.metadata, PageCursor{state.metadata.getStartPageIdx(), 0},
            state.metadata.compMeta.floatMetadata()->exceptionCapacity);
    // for ALP exceptions we don't care about the statistics
    const auto compMeta =
        CompressionMetadata(StorageValue{0}, StorageValue{1}, CompressionType::UNCOMPRESSED);
    const auto exceptionChunkMeta = ColumnChunkMetadata(exceptionBaseCursor.pageIdx,
        safeIntegerConversion<page_idx_t>(
            EncodeException<T>::numPagesFromExceptions(exceptionCapacity)),
        exceptionCapacity, compMeta);
    chunkState = std::make_unique<SegmentState>(exceptionChunkMeta,
        EncodeException<T>::exceptionBytesPerPage() / EncodeException<T>::sizeInBytes());

    chunkData =
        std::make_unique<ColumnChunkData>(*memoryManager, physicalType, false, exceptionChunkMeta,
            false /*all written data is non-null and nulls are kept in a separate mask in-memory*/);
    chunkData->setToInMemory();
    column->scanSegment(*chunkState, chunkData.get(), 0, chunkState->metadata.numValues);
}

template<std::floating_point T>
InMemoryExceptionChunk<T>::~InMemoryExceptionChunk() = default;

template<std::floating_point T>
void InMemoryExceptionChunk<T>::finalizeAndFlushToDisk(SegmentState& state) {
    finalize(state);

    column->writeSegment(*chunkData, *chunkState, 0, *chunkData, 0, exceptionCapacity);
}

template<std::floating_point T>
void InMemoryExceptionChunk<T>::finalize(SegmentState& state) {
    // removes holes + sorts exception chunk
    finalizedExceptionCount = 0;
    for (size_t i = 0; i < exceptionCount; ++i) {
        if (!emptyMask.isNull(i)) {
            ++finalizedExceptionCount;
            if (finalizedExceptionCount - 1 == i) {
                continue;
            }
            writeException(getExceptionAt(i), finalizedExceptionCount - 1);
        }
    }

    KU_ASSERT(
        finalizedExceptionCount <= state.metadata.compMeta.floatMetadata()->exceptionCapacity);
    state.metadata.compMeta.floatMetadata()->exceptionCount = finalizedExceptionCount;

    ExceptionInBuffer<T>* exceptionWordBuffer =
        reinterpret_cast<ExceptionInBuffer<T>*>(chunkData->getData());
    std::sort(exceptionWordBuffer, exceptionWordBuffer + finalizedExceptionCount,
        [](ExceptionInBuffer<T>& a, ExceptionInBuffer<T>& b) {
            return EncodeExceptionView<T>{reinterpret_cast<std::byte*>(&a)}.getValue() <
                   EncodeExceptionView<T>{reinterpret_cast<std::byte*>(&b)}.getValue();
        });
    std::memset(chunkData->getData() + finalizedExceptionCount * EncodeException<T>::sizeInBytes(),
        0, (exceptionCount - finalizedExceptionCount) * EncodeException<T>::sizeInBytes());
    emptyMask.setNullFromRange(0, finalizedExceptionCount, false);
    emptyMask.setNullFromRange(finalizedExceptionCount, (exceptionCount - finalizedExceptionCount),
        true);
    exceptionCount = finalizedExceptionCount;
    chunkData->setNumValues(finalizedExceptionCount);
}

template<std::floating_point T>
void InMemoryExceptionChunk<T>::addException(EncodeException<T> exception) {
    KU_ASSERT(exceptionCount < exceptionCapacity);
    ++exceptionCount;
    writeException(exception, exceptionCount - 1);
    emptyMask.setNull(exceptionCount - 1, false);
}

template<std::floating_point T>
void InMemoryExceptionChunk<T>::removeExceptionAt(size_t exceptionIdx) {
    // removing an exception does not free up space in the exception buffer
    emptyMask.setNull(exceptionIdx, true);
}

template<std::floating_point T>
EncodeException<T> InMemoryExceptionChunk<T>::getExceptionAt(size_t exceptionIdx) const {
    KU_ASSERT(exceptionIdx < exceptionCount);
    auto bytesInBuffer = chunkData->getValue<ExceptionInBuffer<T>>(exceptionIdx);
    return EncodeExceptionView<T>{reinterpret_cast<std::byte*>(&bytesInBuffer)}.getValue();
}

template<std::floating_point T>
void InMemoryExceptionChunk<T>::writeException(EncodeException<T> exception, size_t exceptionIdx) {
    KU_ASSERT(exceptionIdx < exceptionCount);
    EncodeExceptionView<T>{reinterpret_cast<std::byte*>(chunkData->getData())}.setValue(exception,
        exceptionIdx);
}

template<std::floating_point T>
offset_t InMemoryExceptionChunk<T>::findFirstExceptionAtOrPastOffset(offset_t offsetInChunk) const {
    // binary search for chunkOffset in exceptions
    // we only search among non-finalized exceptions

    offset_t lo = 0;
    offset_t hi = finalizedExceptionCount;
    while (lo < hi) {
        const size_t curExceptionIdx = (lo + hi) / 2;
        EncodeException<T> lastException = getExceptionAt(curExceptionIdx);

        if (lastException.posInChunk < offsetInChunk) {
            lo = curExceptionIdx + 1;
        } else {
            hi = curExceptionIdx;
        }
    }

    return lo;
}

template<std::floating_point T>
PageCursor InMemoryExceptionChunk<T>::getExceptionPageCursor(const ColumnChunkMetadata& metadata,
    PageCursor pageBaseCursor, size_t exceptionCapacity) {
    const size_t numExceptionPages = EncodeException<T>::numPagesFromExceptions(exceptionCapacity);
    const size_t exceptionPageOffset = metadata.getNumPages() - numExceptionPages;
    KU_ASSERT(exceptionPageOffset == (page_idx_t)exceptionPageOffset);
    return {pageBaseCursor.pageIdx + (page_idx_t)exceptionPageOffset, 0};
}

template<std::floating_point T>
size_t InMemoryExceptionChunk<T>::getExceptionCount() const {
    return finalizedExceptionCount;
}

template class InMemoryExceptionChunk<float>;
template class InMemoryExceptionChunk<double>;

} // namespace lbug::storage
