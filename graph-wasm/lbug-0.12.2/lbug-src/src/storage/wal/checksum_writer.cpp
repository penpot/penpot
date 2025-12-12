#include "storage/wal/checksum_writer.h"

#include <cstring>

#include "common/checksum.h"
#include "common/serializer/serializer.h"
#include <bit>

namespace lbug::storage {
static constexpr uint64_t INITIAL_BUFFER_SIZE = common::LBUG_PAGE_SIZE;

ChecksumWriter::ChecksumWriter(std::shared_ptr<common::Writer> outputWriter,
    MemoryManager& memoryManager)
    : outputSerializer(std::move(outputWriter)),
      entryBuffer(memoryManager.allocateBuffer(false, INITIAL_BUFFER_SIZE)) {}

static void resizeBufferIfNeeded(std::unique_ptr<MemoryBuffer>& entryBuffer,
    uint64_t requestedSize) {
    const auto currentBufferSize = entryBuffer->getBuffer().size_bytes();
    if (requestedSize > currentBufferSize) {
        auto* memoryManager = entryBuffer->getMemoryManager();
        entryBuffer = memoryManager->allocateBuffer(false, std::bit_ceil(requestedSize));
    }
}

void ChecksumWriter::write(const uint8_t* data, uint64_t size) {
    if (currentEntrySize.has_value()) {
        resizeBufferIfNeeded(entryBuffer, *currentEntrySize + size);
        std::memcpy(entryBuffer->getData() + *currentEntrySize, data, size);
        *currentEntrySize += size;
    } else {
        // The data we are writing does not need to be checksummed
        outputSerializer.write(data, size);
    }
}

void ChecksumWriter::clear() {
    currentEntrySize.reset();
    outputSerializer.getWriter()->clear();
}

void ChecksumWriter::flush() {
    outputSerializer.getWriter()->flush();
}

void ChecksumWriter::onObjectBegin() {
    currentEntrySize.emplace(0);
}

void ChecksumWriter::onObjectEnd() {
    KU_ASSERT(currentEntrySize.has_value());
    const auto checksum = common::checksum(entryBuffer->getData(), *currentEntrySize);
    outputSerializer.write(entryBuffer->getData(), *currentEntrySize);
    outputSerializer.serializeValue(checksum);
    currentEntrySize.reset();
}

uint64_t ChecksumWriter::getSize() const {
    return currentEntrySize.value_or(0) + outputSerializer.getWriter()->getSize();
}

void ChecksumWriter::sync() {
    outputSerializer.getWriter()->sync();
}

} // namespace lbug::storage
