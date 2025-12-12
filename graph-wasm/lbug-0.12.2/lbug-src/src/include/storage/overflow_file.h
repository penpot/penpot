#pragma once

#include <functional>
#include <memory>
#include <string_view>
#include <vector>

#include "common/types/types.h"
#include "storage//file_handle.h"
#include "storage/index/hash_index_utils.h"
#include "storage/storage_utils.h"

namespace lbug {
namespace storage {

class OverflowFile;

// Stores the current state of the overflow file
// The cursors in use are stored here so that we can write new pages directly
// to the overflow file, and in the case of an interruption and rollback the header will
// still record the correct place in the file to allocate new pages
//
// The first page managed by each handle is also stored for cases where we wish to iterate through
// all the managed pages (e.g. when reclaiming pages)
struct StringOverflowFileHeader {
    struct Entry {
        common::page_idx_t startPageIdx{common::INVALID_PAGE_IDX};
        PageCursor cursor;
    } entries[NUM_HASH_INDEXES];

    // pages starts at one to reserve space for this header
    StringOverflowFileHeader() : entries{} {}
};
static_assert(std::has_unique_object_representations_v<StringOverflowFileHeader>);

class OverflowFileHandle {

public:
    OverflowFileHandle(OverflowFile& overflowFile, StringOverflowFileHeader::Entry& entry)
        : startPageIdx(entry.startPageIdx), nextPosToWriteTo(entry.cursor),
          overflowFile{overflowFile} {}
    // The OverflowFile stores the handles and returns pointers to them.
    // Moving the handle would invalidate those pointers
    OverflowFileHandle(OverflowFileHandle&& other) = delete;

    std::string readString(transaction::TransactionType trxType,
        const common::ku_string_t& str) const;

    bool equals(transaction::TransactionType trxType, std::string_view keyToLookup,
        const common::ku_string_t& keyInEntry) const;

    common::ku_string_t writeString(PageAllocator* pageAllocator, std::string_view rawString);
    common::ku_string_t writeString(PageAllocator* pageAllocator, const char* rawString) {
        return writeString(pageAllocator, std::string_view(rawString));
    }

    void checkpoint();
    void checkpointInMemory() { pageWriteCache.clear(); }
    void rollbackInMemory(PageCursor nextPosToWriteTo_) {
        pageWriteCache.clear();
        this->nextPosToWriteTo = nextPosToWriteTo_;
    }
    void reclaimStorage(PageAllocator& pageAllocator);

private:
    uint8_t* addANewPage(PageAllocator* pageAllocator);
    void setStringOverflow(PageAllocator* pageAllocator, const char* inMemSrcStr, uint64_t len,
        common::ku_string_t& diskDstString);

    void read(transaction::TransactionType trxType, common::page_idx_t pageIdx,
        const std::function<void(uint8_t*)>& func) const;

private:
    static constexpr common::page_idx_t END_OF_PAGE =
        common::LBUG_PAGE_SIZE - sizeof(common::page_idx_t);
    // Index of the first page managed by this handle
    common::page_idx_t& startPageIdx;
    // This is the index of the last free byte to which we can write.
    PageCursor& nextPosToWriteTo;
    OverflowFile& overflowFile;

    struct CachedPage {
        std::unique_ptr<MemoryBuffer> buffer;
        bool newPage = false;
    };

    // Cached pages which have been written in the current transaction
    std::unordered_map<common::page_idx_t, CachedPage> pageWriteCache;
};

class ShadowFile;
class OverflowFile {
    friend class OverflowFileHandle;

public:
    // For reading an existing overflow file
    OverflowFile(FileHandle* fileHandle, MemoryManager& memoryManager, ShadowFile* shadowFile,
        common::page_idx_t headerPageIdx);

    virtual ~OverflowFile() = default;

    // Handles contain a reference to the overflow file
    OverflowFile(OverflowFile&& other) = delete;

    void rollbackInMemory();
    void checkpoint(PageAllocator& pageAllocator);
    void checkpointInMemory();

    void reclaimStorage(PageAllocator& pageAllocator) const;

    common::page_idx_t getHeaderPageIdx() const { return headerPageIdx; }

    OverflowFileHandle* addHandle() {
        KU_ASSERT(handles.size() < NUM_HASH_INDEXES);
        handles.emplace_back(
            std::make_unique<OverflowFileHandle>(*this, header.entries[handles.size()]));
        return handles.back().get();
    }

    FileHandle* getFileHandle() const {
        KU_ASSERT(fileHandle);
        return fileHandle;
    }

protected:
    explicit OverflowFile(MemoryManager& memoryManager);

    common::page_idx_t getNewPageIdx(PageAllocator* pageAllocator);

private:
    void readFromDisk(transaction::TransactionType trxType, common::page_idx_t pageIdx,
        const std::function<void(uint8_t*)>& func) const;

    // Writes new pages directly to the file and existing pages to the WAL
    void writePageToDisk(common::page_idx_t pageIdx, uint8_t* data, bool newPage) const;

protected:
    static constexpr uint64_t HEADER_PAGE_IDX = 0;

    std::vector<std::unique_ptr<OverflowFileHandle>> handles;
    StringOverflowFileHeader header;
    FileHandle* fileHandle;
    ShadowFile* shadowFile;
    MemoryManager& memoryManager;
    std::atomic<common::page_idx_t> pageCounter;
    std::atomic<bool> headerChanged;
    common::page_idx_t headerPageIdx;
};

class InMemOverflowFile final : public OverflowFile {
public:
    explicit InMemOverflowFile(MemoryManager& memoryManager) : OverflowFile{memoryManager} {}
};

} // namespace storage
} // namespace lbug
