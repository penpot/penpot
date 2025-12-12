#pragma once

#include <mutex>

#include "common/constants.h"
#include "common/types/types.h"

namespace lbug {
namespace storage {

// A VMRegion holds a virtual memory region of a certain size allocated through mmap.
// The region is divided into frame groups, each of which is a group of frames of the same size.
// Each FileHandle should grab a frame group each time when they add a new file page group (see
// `FileHandle::addNewPageGroupWithoutLock`). In this way, each file page group uniquely
// corresponds to a frame group, thus, a page also uniquely corresponds to a frame in a VMRegion.
class VMRegion {
    friend class BufferManager;

public:
    explicit VMRegion(common::PageSizeClass pageSizeClass, uint64_t maxRegionSize);
    ~VMRegion();

    common::frame_group_idx_t addNewFrameGroup();

    // Use `MADV_DONTNEED` to release physical memory associated with this frame.
    void releaseFrame(common::frame_idx_t frameIdx) const;

    // Returns true if the memory address is within the reserved virtual memory region
    bool contains(const uint8_t* address) const {
        return address >= region && address < region + getMaxRegionSize();
    }
    inline uint8_t* getFrame(common::frame_idx_t frameIdx) const {
        return region + (static_cast<std::uint64_t>(frameIdx) * frameSize);
    }

private:
    inline uint64_t getMaxRegionSize() const {
        return maxNumFrameGroups * frameSize * common::StorageConstants::PAGE_GROUP_SIZE;
    }

private:
    std::mutex mtx;
    uint8_t* region;
    uint32_t frameSize;
    uint64_t numFrameGroups;
    uint64_t maxNumFrameGroups;
};

} // namespace storage
} // namespace lbug
