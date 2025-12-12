#include "storage/buffer_manager/buffer_manager.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <thread>

#include "common/assert.h"
#include "common/constants.h"
#include "common/exception/buffer_manager.h"
#include "common/file_system/local_file_system.h"
#include "common/file_system/virtual_file_system.h"
#include "common/types/types.h"
#include "main/db_config.h"
#include "storage/buffer_manager/spiller.h"
#include "storage/file_handle.h"
#include "storage/table/column_chunk_data.h"
#include <span>

#if defined(_WIN32)
#include <exception>

#include <eh.h>
#include <errhandlingapi.h>
#include <memoryapi.h>
#include <windows.h>
#include <winnt.h>
#endif

using namespace lbug::common;

namespace lbug {
namespace storage {

bool EvictionQueue::insert(uint32_t fileIndex, page_idx_t pageIndex) {
    EvictionCandidate candidate{fileIndex, pageIndex};
    while (size < capacity) {
        // Weak is fine since spurious failure is acceptable.
        // The slot can always be filled later.
        auto emptyCandidate = EMPTY;
        if (data[insertCursor.fetch_add(1, std::memory_order_relaxed) % capacity]
                .compare_exchange_weak(emptyCandidate, candidate)) {
            size++;
            return true;
        }
    }
    return false;
}

std::span<std::atomic<EvictionCandidate>, EvictionQueue::BATCH_SIZE> EvictionQueue::next() {
    return std::span<std::atomic<EvictionCandidate>, BATCH_SIZE>(
        data.get() + ((evictionCursor += BATCH_SIZE) % capacity), BATCH_SIZE);
}

void EvictionQueue::clear(std::atomic<EvictionCandidate>& candidate) {
    auto nonEmpty = candidate.load();
    if (nonEmpty != EMPTY && candidate.compare_exchange_strong(nonEmpty, EMPTY)) {
        size--;
        return;
    }
    KU_UNREACHABLE;
}

BufferManager::BufferManager(const std::string& databasePath, const std::string& spillToDiskPath,
    uint64_t bufferPoolSize, uint64_t maxDBSize, VirtualFileSystem* vfs, bool readOnly)
    : bufferPoolSize{bufferPoolSize}, evictionQueue{bufferPoolSize / LBUG_PAGE_SIZE},
      usedMemory{evictionQueue.getCapacity() * sizeof(EvictionCandidate)}, vfs{vfs} {
    verifySizeParams(bufferPoolSize, maxDBSize);
#if !BM_MALLOC
    vmRegions[0] = std::make_unique<VMRegion>(REGULAR_PAGE, maxDBSize);
    vmRegions[1] = std::make_unique<VMRegion>(TEMP_PAGE, bufferPoolSize);
#endif

    // TODO(bmwinger): It may be better to spill to disk in a different location for remote file
    // systems, or even in general.
    // Ideally we want to spill to disk in some temporary location such
    // as /var/tmp (not /tmp since that may be backed by memory). However we also need to be able to
    // support multiple databases spilling at once (can't be the same file), and handle different
    // platforms.
    if (!readOnly && !main::DBConfig::isDBPathInMemory(databasePath) &&
        dynamic_cast<LocalFileSystem*>(vfs->findFileSystem(spillToDiskPath))) {
        spiller = std::make_unique<Spiller>(spillToDiskPath, *this, vfs);
    }
}

void BufferManager::verifySizeParams(uint64_t bufferPoolSize, uint64_t maxDBSize) {
    if (bufferPoolSize < LBUG_PAGE_SIZE) {
        throw BufferManagerException(stringFormat(
            "The given buffer pool size should be at least {} bytes.", LBUG_PAGE_SIZE));
    }
    // We require at least two page groups, one for the main data file, and one for the shadow file.
    if (maxDBSize < 2 * LBUG_PAGE_SIZE * StorageConstants::PAGE_GROUP_SIZE) {
        throw BufferManagerException(
            "The given max db size should be at least " +
            std::to_string(2 * LBUG_PAGE_SIZE * StorageConstants::PAGE_GROUP_SIZE) + " bytes.");
    }
    if ((maxDBSize & (maxDBSize - 1)) != 0) {
        throw BufferManagerException("The given max db size should be a power of 2.");
    }
}

// Important Note: Pin returns a raw pointer to the frame. This is potentially very dangerous and
// trusts the caller is going to protect this memory space.
// Important responsibilities for the caller are:
// (1) The caller should know the page size and not read/write beyond these boundaries.
// (2) If the given FileHandle is not a (temporary) in-memory file and the caller writes to the
// frame, caller should make sure to call setFrameDirty to let the BufferManager know that the page
// should be flushed to disk if it is evicted.
// (3) If multiple threads are writing to the page, they should coordinate separately because they
// both get access to the same piece of memory.
uint8_t* BufferManager::pin(FileHandle& fileHandle, page_idx_t pageIdx,
    PageReadPolicy pageReadPolicy) {
    auto pageState = fileHandle.getPageState(pageIdx);
    while (true) {
        auto currStateAndVersion = pageState->getStateAndVersion();
        switch (PageState::getState(currStateAndVersion)) {
        case PageState::EVICTED: {
            if (pageState->tryLock(currStateAndVersion)) {
                if (!claimAFrame(fileHandle, pageIdx, pageReadPolicy)) {
                    pageState->resetToEvicted();
                    throw BufferManagerException("Unable to allocate memory! The buffer pool is "
                                                 "full and no memory could be freed!");
                }
                if (!evictionQueue.insert(fileHandle.getFileIndex(), pageIdx)) {
                    throw BufferManagerException(
                        "Eviction queue is full! This should be impossible.");
                }
#if BM_MALLOC
                KU_ASSERT(pageState->getPage());
                return pageState->getPage();
#else
                return getFrame(fileHandle, pageIdx);
#endif
            }
        } break;
        case PageState::UNLOCKED:
        case PageState::MARKED: {
            if (pageState->tryLock(currStateAndVersion)) {
                return getFrame(fileHandle, pageIdx);
            }
        } break;
        case PageState::LOCKED: {
            continue;
        }
        default: {
            KU_UNREACHABLE;
        }
        }
    }
}

#if defined(WIN32)
class AccessViolation : public std::exception {
public:
    AccessViolation(const uint8_t* location) : location{location} {}

    const uint8_t* location;
};

class ScopedTranslator {
    const _se_translator_function old;

public:
    ScopedTranslator(_se_translator_function newTranslator)
        : old{_set_se_translator(newTranslator)} {}
    ~ScopedTranslator() { _set_se_translator(old); }
};

void handleAccessViolation(unsigned int exceptionCode, PEXCEPTION_POINTERS exceptionRecord) {
    if (exceptionCode == EXCEPTION_ACCESS_VIOLATION
        // exception was from a read
        && exceptionRecord->ExceptionRecord->ExceptionInformation[0] == 0) [[likely]] {
        throw AccessViolation(
            (const uint8_t*)exceptionRecord->ExceptionRecord->ExceptionInformation[1]);
    }
    // Needs to not be an Exception so that it can't be caught by regular exception handling
    // And is seems like throwing integer error codes is treated similarly to hardware
    // exceptions with /EHa
    throw exceptionCode;
}
#endif

// Returns true if the function completes successfully
inline bool try_func(const std::function<void(uint8_t*)>& func, uint8_t* frame,
    const std::array<std::unique_ptr<VMRegion>, 2>& vmRegions [[maybe_unused]],
    PageSizeClass pageSizeClass [[maybe_unused]], [[maybe_unused]] PageState* pageState) {
#if BM_MALLOC
    if (frame == nullptr) {
        return false;
    }
    pageState->addReader();
#endif

#if defined(_WIN32) && !BM_MALLOC
    try {
#endif
        func(frame);
#if defined(_WIN32) && !BM_MALLOC
    } catch (AccessViolation& exc) {
        // If we encounter an acess violation within the VM region,
        // the page was decomitted by another thread
        // and is no longer valid memory
        if (vmRegions[pageSizeClass]->contains(exc.location)) {
            return false;
        } else {
            throw EXCEPTION_ACCESS_VIOLATION;
        }
    }
#endif
#if BM_MALLOC
    pageState->removeReader();
#endif
    return true;
}

void BufferManager::optimisticRead(FileHandle& fileHandle, page_idx_t pageIdx,
    const std::function<void(uint8_t*)>& func) {
    auto pageState = fileHandle.getPageState(pageIdx);
#if defined(_WIN32)
    // Change the Structured Exception handling just for the scope of this function
    auto translator = ScopedTranslator(handleAccessViolation);
#endif
    while (true) {
        auto currStateAndVersion = pageState->getStateAndVersion();
        switch (PageState::getState(currStateAndVersion)) {
        case PageState::UNLOCKED: {
            if (!try_func(func, getFrame(fileHandle, pageIdx), vmRegions,
                    fileHandle.getPageSizeClass(), pageState)) {
                continue;
            }
            if (pageState->getStateAndVersion() == currStateAndVersion) {
                return;
            }
        } break;
        case PageState::MARKED: {
            // If the page is marked, we try to switch to unlocked.
            pageState->tryClearMark(currStateAndVersion);
            continue;
        }
        case PageState::EVICTED: {
            pin(fileHandle, pageIdx, PageReadPolicy::READ_PAGE);
            unpin(fileHandle, pageIdx);
        } break;
        default: {
            // When locked, continue the spinning.
            continue;
        }
        }
    }
}

void BufferManager::unpin(FileHandle& fileHandle, page_idx_t pageIdx) {
    auto pageState = fileHandle.getPageState(pageIdx);
    pageState->unlock();
}

// evicts up to 64 pages and returns the space reclaimed
uint64_t BufferManager::evictPages() {
    std::array<std::atomic<EvictionCandidate>*, EvictionQueue::BATCH_SIZE> evictionCandidates{};
    size_t evictablePages = 0;
    uint64_t claimedMemory = 0;

    // Try each page at least twice.
    // E.g. if the vast majority of pages are unmarked and unlocked,
    // the first pass will mark them and the second pass, if insufficient marked pages
    // are found, will evict the first batch.
    // Using the eviction queue's cursor means that we fail after the same number of total attempts,
    // regardless of how many threads are trying to evict.
    auto startCursor = evictionQueue.getEvictionCursor();
    auto failureLimit = evictionQueue.getCapacity() * 2;
    while (evictablePages == 0 && evictionQueue.getEvictionCursor() - startCursor < failureLimit) {
        for (auto& candidate : evictionQueue.next()) {
            auto evictionCandidate = candidate.load();
            if (evictionCandidate == EvictionQueue::EMPTY) {
                continue;
            }
            KU_ASSERT(evictionCandidate.fileIdx < fileHandles.size());
            auto* pageState =
                fileHandles[evictionCandidate.fileIdx]->getPageState(evictionCandidate.pageIdx);
            auto pageStateAndVersion = pageState->getStateAndVersion();
            if (!evictionCandidate.isEvictable(pageStateAndVersion)) {
                if (evictionCandidate.isSecondChanceEvictable(pageStateAndVersion)) {
                    pageState->tryMark(pageStateAndVersion);
                }
                continue;
            }
            evictionCandidates[evictablePages++] = &candidate;
        }
    }

    for (size_t i = 0; i < evictablePages; i++) {
        claimedMemory += tryEvictPage(*evictionCandidates[i]);
    }
    return claimedMemory;
}

void BufferManager::removeEvictedCandidates() {
    auto startCursor = evictionQueue.getEvictionCursor();
    while (evictionQueue.getEvictionCursor() - startCursor < evictionQueue.getCapacity()) {
        for (auto& candidate : evictionQueue.next()) {
            auto evictionCandidate = candidate.load();
            if (evictionCandidate == EvictionQueue::EMPTY) {
                continue;
            }
            KU_ASSERT(evictionCandidate.fileIdx < fileHandles.size());
            auto* pageState =
                fileHandles[evictionCandidate.fileIdx]->getPageState(evictionCandidate.pageIdx);
            auto pageStateAndVersion = pageState->getStateAndVersion();
            if (PageState::getState(pageStateAndVersion) == PageState::EVICTED) {
                evictionQueue.clear(candidate);
            }
        }
    }
}

// This function tries to load the given page into a frame. Due to our design of mmap, each page is
// uniquely mapped to a frame. Thus, claiming a frame is equivalent to ensuring enough physical
// memory is available.
// First, we reserve the memory for the page, which increments the atomic counter `usedMemory`.
// Then, we check if there is enough memory available. If not, we evict pages until we have enough
// or we can find no more pages to be evicted.
// Lastly, we double check if the needed memory is available. If not, we free the memory we reserved
// and return false, otherwise, we load the page to its corresponding frame and return true.
bool BufferManager::claimAFrame(FileHandle& fileHandle, page_idx_t pageIdx,
    PageReadPolicy pageReadPolicy) {
    page_offset_t pageSizeToClaim = fileHandle.getPageSize();
    if (!reserve(pageSizeToClaim)) {
        return false;
    }
#if _WIN32 && !BM_MALLOC
    // Committing in this context means reserving physical memory/page file space for a segment of
    // virtual memory. On Linux/Unix this is automatic when you write to the memory address.
    auto result =
        VirtualAlloc(getFrame(fileHandle, pageIdx), pageSizeToClaim, MEM_COMMIT, PAGE_READWRITE);
    if (result == NULL) {
        throw BufferManagerException(
            stringFormat("VirtualAlloc MEM_COMMIT failed with error code {}: {}.", GetLastError(),
                std::system_category().message(GetLastError())));
    }
#endif
    cachePageIntoFrame(fileHandle, pageIdx, pageReadPolicy);
    return true;
}

bool BufferManager::reserve(uint64_t sizeToReserve) {
    // Reserve the memory for the page.
    usedMemory += sizeToReserve;
    uint64_t totalClaimedMemory = 0;
    uint64_t nonEvictableClaimedMemory = 0;
    const auto needMoreMemory = [&]() {
        // The only time we should exceed the buffer pool size should be when threads are currently
        // attempting to reserve space and have pre-allocated space. So if we've claimed enough
        // space for what we're trying to reserve, then we can continue even if the current total is
        // higher than the buffer pool size as we should never actually exceed the buffer pool size.
        return sizeToReserve > totalClaimedMemory &&
               // usedMemory - totalClaimedMemory could underflow
               usedMemory > bufferPoolSize.load() - totalClaimedMemory;
    };
    uint8_t failedCount = 0;
    // Evict pages if necessary until we have enough memory.
    while (needMoreMemory()) {
        uint64_t memoryClaimed = 0;
        // Avoid reducing the evictable memory below 1/2 at first to reduce thrashing if most of the
        // memory is non-evictable
        if (!spiller || usedMemory - nonEvictableMemory > bufferPoolSize / 2) {
            memoryClaimed = evictPages();
        } else {
            auto [_memoryClaimed, nowEvictableMemory] = spiller->claimNextGroup();
            memoryClaimed = _memoryClaimed;
            nonEvictableClaimedMemory += _memoryClaimed;
            nonEvictableMemory -= nowEvictableMemory;
            // If we're unable to claim anything from the spiller, fall back to evicting pages
            // We may also need to evict pages if the spiller just unpins BM pages
            if (memoryClaimed == 0 || nowEvictableMemory > 0) {
                memoryClaimed = evictPages();
            }
        }
        if (memoryClaimed == 0 && needMoreMemory()) {
            if (failedCount++ < 2) {
                // If we failed to find any memory to free, try waiting briefly for other threads to
                // stop using memory
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            } else {
                // Cannot find more pages to be evicted. Free the memory we reserved and return
                // false.
                freeUsedMemory(sizeToReserve + totalClaimedMemory);
                nonEvictableMemory -= nonEvictableClaimedMemory;
                return false;
            }
        }
        totalClaimedMemory += memoryClaimed;
    }
    // Have enough memory available now
    if (totalClaimedMemory > 0) {
        freeUsedMemory(totalClaimedMemory);
        nonEvictableMemory -= nonEvictableClaimedMemory;
    }
    return true;
}

uint64_t BufferManager::tryEvictPage(std::atomic<EvictionCandidate>& _candidate) {
    auto candidate = _candidate.load();
    // Page must have been evicted by another thread already
    if (candidate.pageIdx == INVALID_PAGE_IDX) {
        return 0;
    }
    auto& pageState = *fileHandles[candidate.fileIdx]->getPageState(candidate.pageIdx);
    auto currStateAndVersion = pageState.getStateAndVersion();
    // We check if the page is evictable again. Note that if the page's state or version has
    // changed after the check, `tryLock` will fail, and we will abort the eviction of this page.
    if (!candidate.isEvictable(currStateAndVersion) || !pageState.tryLock(currStateAndVersion)) {
        return 0;
    }
    // The pageState was locked, but another thread already evicted this candidate and unlocked it
    // before the lock occurred
    if (_candidate.load() != candidate
#if BM_MALLOC
        // When the pageState is locked, optimisticReads will wait, so at this point no new
        // optimistic reads will begin and thus it is safe to free the buffer at this point
        || pageState.getReaderCount() > 0
#endif
    ) {
        pageState.unlockUnchanged();
        return 0;
    }
    if (fileHandles[candidate.fileIdx]->isInMemoryMode()) {
        // Cannot flush pages under in memory mode.
        return 0;
    }
    // At this point, the page is LOCKED, and we have exclusive access to the eviction candidate.
    // Next, flush out the frame into the file page if the frame
    // is dirty. Finally remove the page from the frame and reset the page to EVICTED.
    auto& fileHandle = *fileHandles[candidate.fileIdx];
    fileHandle.flushPageIfDirtyWithoutLock(candidate.pageIdx);
    auto numBytesFreed = fileHandle.getPageSize();
    releaseFrameForPage(fileHandle, candidate.pageIdx);
    pageState.resetToEvicted();
    evictionQueue.clear(_candidate);
    return numBytesFreed;
}

void BufferManager::cachePageIntoFrame(FileHandle& fileHandle, page_idx_t pageIdx,
    PageReadPolicy pageReadPolicy) {
    auto pageState = fileHandle.getPageState(pageIdx);
    pageState->clearDirty();
#if BM_MALLOC
    pageState->allocatePage(fileHandle.getPageSize());
    if (pageReadPolicy == PageReadPolicy::READ_PAGE) {
        fileHandle.readPageFromDisk(pageState->getPage(), pageIdx);
    }
#else
    if (pageReadPolicy == PageReadPolicy::READ_PAGE) {
        fileHandle.readPageFromDisk(getFrame(fileHandle, pageIdx), pageIdx);
    }
#endif
}

void BufferManager::removeFilePagesFromFrames(FileHandle& fileHandle) {
    for (auto pageIdx = 0u; pageIdx < fileHandle.getNumPages(); ++pageIdx) {
        removePageFromFrame(fileHandle, pageIdx, false /* do not flush */);
    }
}

void BufferManager::updateFrameIfPageIsInFrameWithoutLock(file_idx_t fileIdx,
    const uint8_t* newPage, page_idx_t pageIdx) {
    KU_ASSERT(fileIdx < fileHandles.size());
    auto& fileHandle = *fileHandles[fileIdx];
    auto state = fileHandle.getPageState(pageIdx);
    if (state && state->getState() != PageState::EVICTED) {
        memcpy(getFrame(fileHandle, pageIdx), newPage, LBUG_PAGE_SIZE);
    }
}

void BufferManager::removePageFromFrameIfNecessary(FileHandle& fileHandle, page_idx_t pageIdx) {
    if (pageIdx >= fileHandle.getNumPages()) {
        return;
    }
    removePageFromFrame(fileHandle, pageIdx, false /* do not flush */);
}

// NOTE: We assume the page is not pinned (locked) here.
void BufferManager::removePageFromFrame(FileHandle& fileHandle, page_idx_t pageIdx,
    bool shouldFlush) {
    auto pageState = fileHandle.getPageState(pageIdx);
    if (PageState::getState(pageState->getStateAndVersion()) == PageState::EVICTED) {
        return;
    }
    pageState->spinLock(pageState->getStateAndVersion());
    if (shouldFlush) {
        fileHandle.flushPageIfDirtyWithoutLock(pageIdx);
    }
    releaseFrameForPage(fileHandle, pageIdx);
    freeUsedMemory(fileHandle.getPageSize());
    pageState->resetToEvicted();
}

uint64_t BufferManager::freeUsedMemory(uint64_t size) {
    KU_ASSERT(usedMemory.load() >= size);
    return usedMemory.fetch_sub(size);
}

void BufferManager::resetSpiller(std::string spillPath) {
    if (spillPath.empty()) {
        // Disable spilling to disk;
        spiller = nullptr;
    } else {
        spiller = std::make_unique<Spiller>(spillPath, *this, vfs);
    }
}

BufferManager::~BufferManager() = default;

} // namespace storage
} // namespace lbug
