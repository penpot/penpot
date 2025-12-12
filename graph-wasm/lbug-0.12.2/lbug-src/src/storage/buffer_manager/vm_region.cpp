#include "storage/buffer_manager/vm_region.h"

#include "common/string_format.h"
#include "common/system_config.h"
#include "common/system_message.h"

#ifdef _WIN32
#include <errhandlingapi.h>
#include <handleapi.h>
#include <memoryapi.h>
#else
#include <sys/mman.h>
#endif

#include "common/exception/buffer_manager.h"

using namespace lbug::common;

namespace lbug {
namespace storage {

VMRegion::VMRegion(PageSizeClass pageSizeClass, uint64_t maxRegionSize) : numFrameGroups{0} {
    if (maxRegionSize > static_cast<std::size_t>(-1)) {
        throw BufferManagerException("maxRegionSize is beyond the max available mmap region size.");
    }
    frameSize = pageSizeClass == REGULAR_PAGE ? LBUG_PAGE_SIZE : TEMP_PAGE_SIZE;
    const auto numBytesForFrameGroup = frameSize * StorageConstants::PAGE_GROUP_SIZE;
    maxNumFrameGroups = (maxRegionSize + numBytesForFrameGroup - 1) / numBytesForFrameGroup;
#ifdef _WIN32
    region = (uint8_t*)VirtualAlloc(NULL, getMaxRegionSize(), MEM_RESERVE, PAGE_READWRITE);
    if (region == NULL) {
        throw BufferManagerException(stringFormat(
            "VirtualAlloc for size {} failed with error code {}: {}.", getMaxRegionSize(),
            GetLastError(), std::system_category().message(GetLastError())));
    }
#else
    // Create a private anonymous mapping. The mapping is not shared with other processes and not
    // backed by any file, and its content are initialized to zero.
    region = static_cast<uint8_t*>(mmap(NULL, getMaxRegionSize(), PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1 /* fd */, 0 /* offset */));
    if (region == MAP_FAILED) {
        throw BufferManagerException(
            "Mmap for size " + std::to_string(getMaxRegionSize()) + " failed.");
    }
#endif
}

VMRegion::~VMRegion() {
#ifdef _WIN32
    VirtualFree(region, 0, MEM_RELEASE);
#else
    munmap(region, getMaxRegionSize());
#endif
}

void VMRegion::releaseFrame(frame_idx_t frameIdx) const {
#ifdef _WIN32
    // TODO: VirtualAlloc(..., MEM_RESET, ...) may be faster
    // See https://arvid.io/2018/04/02/memory-mapping-on-windows/#1
    // Not sure what the differences are
    if (!VirtualFree(getFrame(frameIdx), frameSize, MEM_DECOMMIT)) {
        auto code = GetLastError();
        throw BufferManagerException(stringFormat(
            "Releasing physical memory associated with a frame failed with error code {}: {}.",
            code, systemErrMessage(code)));
    }

#else
    int error = madvise(getFrame(frameIdx), frameSize, MADV_DONTNEED);
    if (error != 0) {
        // LCOV_EXCL_START
        throw BufferManagerException(stringFormat(
            "Releasing physical memory associated with a frame failed with error code {}: {}.",
            error, posixErrMessage()));
        // LCOV_EXCL_STOP
    }
#endif
}

frame_group_idx_t VMRegion::addNewFrameGroup() {
    std::unique_lock xLck{mtx};
    if (numFrameGroups >= maxNumFrameGroups) {
        // LCOV_EXCL_START
        throw BufferManagerException("No more frame groups can be added to the allocator.");
        // LCOV_EXCL_STOP
    }
    return numFrameGroups++;
}

} // namespace storage
} // namespace lbug
