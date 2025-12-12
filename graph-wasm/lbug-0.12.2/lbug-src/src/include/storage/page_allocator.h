#pragma once

#include "storage/page_range.h"

namespace lbug {
namespace storage {

class FileHandle;

class PageAllocator {
public:
    explicit PageAllocator(FileHandle* fileHandle) : dataFH(fileHandle) {}
    virtual ~PageAllocator() = default;

    virtual PageRange allocatePageRange(common::page_idx_t numPages) = 0;
    common::page_idx_t allocatePage() { return allocatePageRange(1).startPageIdx; }

    // Only used during checkpoint
    virtual void freePageRange(PageRange block) = 0;
    void freePage(common::page_idx_t pageIdx) { freePageRange(PageRange(pageIdx, 1)); }

    FileHandle* getDataFH() const { return dataFH; }

private:
    FileHandle* dataFH;
};
} // namespace storage
} // namespace lbug
