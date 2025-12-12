/**
 * We would like to thank Mingkun Ni and Mayank Jasoria for doing the initial research and
 * prototyping for the FreeSpaceManager in their CS 848 course project:
 * https://github.com/ericpolo/lbug_cs848
 */

#pragma once

#include <optional>
#include <set>

#include "common/types/types.h"
namespace lbug::storage {

class BufferManager;
struct PageRange;
struct FreeEntryIterator;
class FileHandle;

class FreeSpaceManager {
public:
    static bool entryCmp(const PageRange& a, const PageRange& b);
    using sorted_free_list_t = std::set<PageRange, decltype(&entryCmp)>;
    using free_list_t = std::vector<PageRange>;

    FreeSpaceManager();

    void addFreePages(PageRange entry);
    void evictAndAddFreePages(FileHandle* fileHandle, PageRange entry);
    std::optional<PageRange> popFreePages(common::page_idx_t numPages);

    // These pages are not reusable until the end of the next checkpoint
    void addUncheckpointedFreePages(PageRange entry);
    void rollbackCheckpoint();

    common::page_idx_t getMaxNumPagesForSerialization() const;
    void serialize(common::Serializer& serializer) const;
    void deserialize(common::Deserializer& deSer);
    void finalizeCheckpoint(FileHandle* fileHandle);

    common::row_idx_t getNumEntries() const;
    std::vector<PageRange> getEntries(common::row_idx_t startOffset,
        common::row_idx_t endOffset) const;

    // When a page is freed by the FSM, it evicts it from the BM. However, if the page is freed,
    // then reused over and over, it can be appended to the eviction queue multiple times. To
    // prevent multiple entries of the same page from existing in the eviction queue, at the end of
    // each checkpoint we remove any already-evicted pages.
    void clearEvictedBufferManagerEntriesIfNeeded(BufferManager* bufferManager);

private:
    PageRange splitPageRange(PageRange chunk, common::page_idx_t numRequiredPages);
    void mergePageRanges(free_list_t newInitialEntries, FileHandle* fileHandle);
    void handleLastPageRange(PageRange pageRange, FileHandle* fileHandle);
    void resetFreeLists();
    static common::idx_t getLevel(common::page_idx_t numPages);
    void evictPages(FileHandle* fileHandle, const PageRange& entry);

    template<typename ValueProcessor>
    void serializeInternal(ValueProcessor& serializer) const;

    std::vector<sorted_free_list_t> freeLists;
    free_list_t uncheckpointedFreePageRanges;
    common::row_idx_t numEntries;
    bool needClearEvictedEntries;
};

/**
 * Used for iterating over all entries in the FreeSpaceManager
 * Note that the iterator may become invalidated in the FSM is modified
 */
struct FreeEntryIterator {
    explicit FreeEntryIterator(const std::vector<FreeSpaceManager::sorted_free_list_t>& freeLists)
        : FreeEntryIterator(freeLists, 0) {}

    FreeEntryIterator(const std::vector<FreeSpaceManager::sorted_free_list_t>& freeLists,
        common::idx_t freeListIdx_)
        : freeLists(freeLists), freeListIdx(freeListIdx_) {
        advanceFreeListIdx();
    }

    void advance(common::row_idx_t numEntries);
    void operator++();
    PageRange operator*() const;
    bool done() const;

    void advanceFreeListIdx();

    const std::vector<FreeSpaceManager::sorted_free_list_t>& freeLists;
    common::idx_t freeListIdx;
    FreeSpaceManager::sorted_free_list_t::const_iterator freeListIt;
};

} // namespace lbug::storage
