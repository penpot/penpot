#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <memory>
#include <shared_mutex>

#include "common/assert.h"
#include "common/concurrent_vector.h"
#include "common/constants.h"
#include "common/copy_constructors.h"
#include "common/file_system/file_info.h"
#include "common/types/types.h"
#include "storage/buffer_manager/page_state.h"
#include "storage/buffer_manager/vm_region.h"
#include "storage/enums/page_read_policy.h"
#include "storage/page_manager.h"

namespace lbug {
namespace main {
class ClientContext;
}

namespace common {
class VirtualFileSystem;
}

namespace storage {
// FileHandle serves several purposes:
// 1) holds basic state information of a file, including FileInfo, flags, pageSize,
// numPages, and pageCapacity.
// 2) provides utility methods to read/write pages from/to the file.
// 3) holds the state of each page in the file in buffer manager. File Handle is the bridge between
// a data structure and the Buffer Manager that abstracts the file in which that data structure is
// stored.

class ShadowFile;
class BufferManager;
class FileHandle {
public:
    friend class BufferManager;
    friend class ShadowFile;

    constexpr static uint8_t isLargePagedMask{0b0000'0001}; // represents 1st least sig. bit (LSB)
    constexpr static uint8_t isNewInMemoryTmpFileMask{0b0000'0010}; // represents 2nd LSB
    // createIfNotExistsMask only applies to existing db files; tmp i-memory files are not created
    constexpr static uint8_t createIfNotExistsMask{0b0000'0100}; // represents 3rd LSB
    constexpr static uint8_t isReadOnlyMask{0b0000'1000};        // represents 4th LSB
    constexpr static uint8_t isLockRequiredMask{0b1000'0000};    // represents 8th LSB

    // READ_ONLY subsumes DEFAULT_PAGED, PERSISTENT, and NO_CREATE.
    constexpr static uint8_t O_PERSISTENT_FILE_READ_ONLY{0b0000'1000};
    constexpr static uint8_t O_PERSISTENT_FILE_CREATE_NOT_EXISTS{0b0000'0100};
    constexpr static uint8_t O_IN_MEM_TEMP_FILE{0b0000'0011};
    constexpr static uint8_t O_PERSISTENT_FILE_IN_MEM{0b0000'0010};
    constexpr static uint8_t O_LOCKED_PERSISTENT_FILE{0b1000'0000};

    FileHandle(const std::string& path, uint8_t fhFlags, BufferManager* bm, uint32_t fileIndex,
        common::VirtualFileSystem* vfs, main::ClientContext* context);
    // File handles are registered with the buffer manager and must not be moved or copied
    DELETE_COPY_AND_MOVE(FileHandle);

    uint8_t* pinPage(common::page_idx_t pageIdx, PageReadPolicy readPolicy);
    void optimisticReadPage(common::page_idx_t pageIdx,
        const std::function<void(uint8_t*)>& readOp);
    // The function assumes that the requested page is already pinned.
    void unpinPage(common::page_idx_t pageIdx);

    // This function assumes the page is already LOCKED.
    void setLockedPageDirty(common::page_idx_t pageIdx) {
        KU_ASSERT(pageIdx < numPages);
        pageStates[pageIdx].setDirty();
    }

    common::file_idx_t getFileIndex() const { return fileIndex; }
    uint8_t* getFrame(common::page_idx_t pageIdx);
    PageState* getPageState(common::page_idx_t pageIdx) { return &pageStates[pageIdx]; }

    // Pages added through these APIs are not tracked by the FSM
    // If allocating pages from the data.kz file it's recommended to do so using the PageManager
    common::page_idx_t addNewPage();
    common::page_idx_t addNewPages(common::page_idx_t numNewPages);

    void removePageIdxAndTruncateIfNecessary(common::page_idx_t pageIdx);
    void removePageFromFrameIfNecessary(common::page_idx_t pageIdx);
    void flushAllDirtyPagesInFrames();

    void readPageFromDisk(uint8_t* frame, common::page_idx_t pageIdx) const {
        KU_ASSERT(!isInMemoryMode());
        KU_ASSERT(pageIdx < numPages);
        fileInfo->readFromFile(frame, getPageSize(), pageIdx * getPageSize());
    }
    void writePageToFile(const uint8_t* buffer, common::page_idx_t pageIdx) {
        KU_ASSERT(pageIdx < numPages);
        writePagesToFile(buffer, getPageSize(), pageIdx);
    }
    void writePagesToFile(const uint8_t* buffer, uint64_t size, common::page_idx_t startPageIdx);

    bool isInMemoryMode() const { return !isLargePaged() && isNewTmpFile(); }

    common::page_idx_t getNumPages() const { return numPages; }
    common::FileInfo* getFileInfo() const { return fileInfo.get(); }
    void resetFileInfo() { fileInfo.reset(); }

    uint64_t getPageSize() const {
        return isLargePaged() ? common::TEMP_PAGE_SIZE : common::LBUG_PAGE_SIZE;
    }

    PageManager* getPageManager() { return pageManager.get(); }

private:
    bool isLargePaged() const { return fhFlags & isLargePagedMask; }
    bool isNewTmpFile() const { return fhFlags & isNewInMemoryTmpFileMask; }
    bool isReadOnlyFile() const { return fhFlags & isReadOnlyMask; }
    bool createFileIfNotExists() const { return fhFlags & createIfNotExistsMask; }
    bool isLockRequired() const { return fhFlags & isLockRequiredMask; }

    common::page_idx_t addNewPageWithoutLock();
    void constructPersistentFileHandle(const std::string& path, common::VirtualFileSystem* vfs,
        main::ClientContext* context);
    void constructTmpFileHandle(const std::string& path);
    common::frame_idx_t getFrameIdx(common::page_idx_t pageIdx) {
        KU_ASSERT(pageIdx < pageCapacity);
        return (frameGroupIdxes[pageIdx >> common::StorageConstants::PAGE_GROUP_SIZE_LOG2]
                   << common::StorageConstants::PAGE_GROUP_SIZE_LOG2) |
               (pageIdx & common::StorageConstants::PAGE_IDX_IN_GROUP_MASK);
    }
    common::PageSizeClass getPageSizeClass() const { return pageSizeClass; }

    void addNewPageGroupWithoutLock();
    common::page_group_idx_t getNumPageGroups() const {
        return ceil(static_cast<double>(numPages) / common::StorageConstants::PAGE_GROUP_SIZE);
    }
    // This function is intended to be used after a fileInfo is created and we want the file
    // to have no pages and page locks. Should be called after ensuring that the buffer manager
    // does not hold any of the pages of the file.
    void resetToZeroPagesAndPageCapacity();
    void flushPageIfDirtyWithoutLock(common::page_idx_t pageIdx);

private:
    // Intended to be used to coordinate calls to functions that change in the internal data
    // structures of the file handle.
    std::shared_mutex fhSharedMutex;

    uint8_t fhFlags;
    std::unique_ptr<common::FileInfo> fileInfo;
    common::file_idx_t fileIndex;
    // Actually allocated/used number of pages in the file.
    std::atomic<uint32_t> numPages;
    // This is the maximum number of pages the filehandle can currently support.
    uint32_t pageCapacity;

    BufferManager* bm;
    common::PageSizeClass pageSizeClass;
    // With a page group size of 2^10 and an 256KB index size, the access cost increases
    // only with each 128GB added to the file
    common::ConcurrentVector<PageState, common::StorageConstants::PAGE_GROUP_SIZE,
        common::TEMP_PAGE_SIZE / sizeof(void*)>
        pageStates;
    // Each file page group corresponds to a frame group in the VMRegion.
    // Just one frame group for each page group, so performance is less sensitive than pageStates
    // and left at the default which won't increase access cost for the frame groups until 16TB of
    // data has been written
    common::ConcurrentVector<common::page_group_idx_t> frameGroupIdxes;

    std::unique_ptr<PageManager> pageManager;
};

} // namespace storage
} // namespace lbug
