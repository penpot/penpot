#pragma once

#include <cstdint>

namespace lbug {
namespace storage {

struct SpillResult {
    uint64_t memoryFreed = 0;
    uint64_t memoryNowEvictable = 0;

    SpillResult& operator+=(const SpillResult& other) {
        memoryFreed += other.memoryFreed;
        memoryNowEvictable += other.memoryNowEvictable;
        return *this;
    }
};

} // namespace storage
} // namespace lbug
