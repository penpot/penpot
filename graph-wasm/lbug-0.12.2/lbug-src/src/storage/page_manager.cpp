#include "storage/page_manager.h"

#include "common/uniq_lock.h"
#include "storage/file_handle.h"
#include "storage/storage_manager.h"

namespace lbug::storage {
static constexpr bool ENABLE_FSM = true;

PageRange PageManager::allocatePageRange(common::page_idx_t numPages) {
    if constexpr (ENABLE_FSM) {
        common::UniqLock lck{mtx};
        auto allocatedFreeChunk = freeSpaceManager->popFreePages(numPages);
        if (allocatedFreeChunk.has_value()) {
            ++version;
            return {*allocatedFreeChunk};
        }
    }
    auto startPageIdx = fileHandle->addNewPages(numPages);
    KU_ASSERT(fileHandle->getNumPages() >= startPageIdx + numPages);
    return PageRange(startPageIdx, numPages);
}

void PageManager::freePageRange(PageRange entry) {
    if constexpr (ENABLE_FSM) {
        common::UniqLock lck{mtx};
        // Freed pages cannot be immediately reused to ensure checkpoint recovery works
        // Instead they are reusable after the end of the next checkpoint
        freeSpaceManager->addUncheckpointedFreePages(entry);
        ++version;
    }
}

common::page_idx_t PageManager::estimatePagesNeededForSerialize() {
    return freeSpaceManager->getMaxNumPagesForSerialization();
}

void PageManager::freeImmediatelyRewritablePageRange(FileHandle* fileHandle, PageRange entry) {
    if constexpr (ENABLE_FSM) {
        common::UniqLock lck{mtx};
        freeSpaceManager->evictAndAddFreePages(fileHandle, entry);
        ++version;
    }
}

void PageManager::serialize(common::Serializer& serializer) {
    freeSpaceManager->serialize(serializer);
}

void PageManager::deserialize(common::Deserializer& deSer) {
    freeSpaceManager->deserialize(deSer);
}

void PageManager::finalizeCheckpoint() {
    freeSpaceManager->finalizeCheckpoint(fileHandle);
}

void PageManager::clearEvictedBMEntriesIfNeeded(BufferManager* bufferManager) {
    freeSpaceManager->clearEvictedBufferManagerEntriesIfNeeded(bufferManager);
}

PageManager* PageManager::Get(const main::ClientContext& context) {
    return StorageManager::Get(context)->getDataFH()->getPageManager();
}

} // namespace lbug::storage
