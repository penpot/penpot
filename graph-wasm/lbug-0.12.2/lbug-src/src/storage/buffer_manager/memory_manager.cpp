#include "storage/buffer_manager/memory_manager.h"

#include <mutex>

#include "common/exception/buffer_manager.h"
#include "common/file_system/virtual_file_system.h"
#include "common/types/types.h"
#include "main/client_context.h"
#include "main/database.h"
#include "storage/buffer_manager/buffer_manager.h"
#include "storage/file_handle.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

MemoryBuffer::MemoryBuffer(MemoryManager* mm, page_idx_t pageIdx, uint8_t* buffer, uint64_t size)
    : buffer{buffer, static_cast<size_t>(size)}, mm{mm}, pageIdx{pageIdx}, evicted{false} {}

MemoryBuffer::~MemoryBuffer() {
    if (buffer.data() != nullptr && !evicted) {
        mm->freeBlock(pageIdx, buffer);
        mm->updateUsedMemoryForFreedBlock(pageIdx, buffer);
        buffer = std::span<uint8_t>();
    }
}

SpillResult MemoryBuffer::setSpilledToDisk(uint64_t filePosition) {
    mm->freeBlock(pageIdx, buffer);
    // reinterpret_cast isn't allowed here, but we shouldn't leave the invalid pointer and
    // still want to store the size
    buffer = std::span(static_cast<uint8_t*>(nullptr), buffer.size());
    evicted = true;
    this->filePosition = filePosition;
    if (pageIdx == INVALID_PAGE_IDX) {
        return SpillResult{buffer.size(), 0};
    } else {
        return SpillResult{0, buffer.size()};
    }
}

void MemoryBuffer::prepareLoadFromDisk() {
    KU_ASSERT(buffer.data() == nullptr && evicted);
    buffer = mm->mallocBuffer(false, buffer.size());
    evicted = false;
}

MemoryManager::MemoryManager(BufferManager* bm, VirtualFileSystem* vfs) : bm{bm} {
    pageSize = TEMP_PAGE_SIZE;
    fh = bm->getFileHandle("mm-256KB", FileHandle::O_IN_MEM_TEMP_FILE, vfs, nullptr);
}

std::span<uint8_t> MemoryManager::mallocBuffer(bool initializeToZero, uint64_t size) {
    if (!bm->reserve(size)) {
        throw BufferManagerException(
            "Unable to allocate memory! The buffer pool is full and no memory could be freed!");
    }
    void* buffer = nullptr;
    bm->nonEvictableMemory += size;
    if (initializeToZero) {
        buffer = calloc(size, 1);
    } else {
        buffer = malloc(size);
    }
    return std::span(static_cast<uint8_t*>(buffer), size);
}

std::unique_ptr<MemoryBuffer> MemoryManager::allocateBuffer(bool initializeToZero, uint64_t size) {
    if (size != TEMP_PAGE_SIZE) [[unlikely]] {
        auto buffer = mallocBuffer(initializeToZero, size);
        return std::make_unique<MemoryBuffer>(this, INVALID_PAGE_IDX, buffer.data(), size);
    }
    page_idx_t pageIdx = INVALID_PAGE_IDX;
    {
        std::scoped_lock<std::mutex> lock(allocatorLock);
        if (freePages.empty()) {
            pageIdx = fh->addNewPage();
        } else {
            pageIdx = freePages.top();
            freePages.pop();
        }
    }
    auto buffer = bm->pin(*fh, pageIdx, PageReadPolicy::DONT_READ_PAGE);
    auto memoryBuffer = std::make_unique<MemoryBuffer>(this, pageIdx, buffer);
    if (initializeToZero) {
        memset(memoryBuffer->getBuffer().data(), 0, pageSize);
    }
    return memoryBuffer;
}

void MemoryManager::freeBlock(page_idx_t pageIdx, std::span<uint8_t> buffer) {
    if (pageIdx == INVALID_PAGE_IDX) {
        std::free(buffer.data());
    } else {
        bm->unpin(*fh, pageIdx);
    }
}

void MemoryManager::updateUsedMemoryForFreedBlock(page_idx_t pageIdx, std::span<uint8_t> buffer) {
    if (pageIdx == INVALID_PAGE_IDX) {
        bm->freeUsedMemory(buffer.size());
        bm->nonEvictableMemory -= buffer.size();
    } else {
        std::unique_lock<std::mutex> lock(allocatorLock);
        freePages.push(pageIdx);
    }
}

MemoryManager* MemoryManager::Get(const main::ClientContext& context) {
    return context.getDatabase()->getMemoryManager();
}

} // namespace storage
} // namespace lbug
