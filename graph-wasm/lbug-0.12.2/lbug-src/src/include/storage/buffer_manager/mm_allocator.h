#pragma once

#include "memory_manager.h"

namespace lbug {
namespace storage {

template<class T>
class MmAllocator {
public:
    using value_type = T;

    explicit MmAllocator(MemoryManager* mm) : mm{mm} {}

    MmAllocator(const MmAllocator& other) : mm{other.mm} {}
    MmAllocator& operator=(const MmAllocator& other) = default;
    DELETE_BOTH_MOVE(MmAllocator);

    [[nodiscard]] T* allocate(const std::size_t size) {
        KU_ASSERT_UNCONDITIONAL(mm != nullptr);
        KU_ASSERT_UNCONDITIONAL(size > 0);
        KU_ASSERT_UNCONDITIONAL(size <= std::numeric_limits<std::size_t>::max() / sizeof(T));

        auto buffer = mm->mallocBuffer(false, size * sizeof(T));
        auto p = reinterpret_cast<T*>(buffer.data());

        // Ensure proper alignment
        KU_ASSERT_UNCONDITIONAL(reinterpret_cast<std::uintptr_t>(p) % alignof(T) == 0);

        return p;
    }

    void deallocate(T* p, const std::size_t size) noexcept {
        KU_ASSERT_UNCONDITIONAL(mm != nullptr);
        KU_ASSERT_UNCONDITIONAL(p != nullptr);
        KU_ASSERT_UNCONDITIONAL(size > 0);

        const auto buffer = std::span(reinterpret_cast<uint8_t*>(p), size * sizeof(T));
        if (buffer.data() != nullptr) {
            mm->freeBlock(common::INVALID_PAGE_IDX, buffer);
        }
    }

private:
    MemoryManager* mm;
};

template<class T, class U>
bool operator==(const MmAllocator<T>& a, const MmAllocator<U>& b) {
    return a.mm == b.mm;
}

} // namespace storage
} // namespace lbug
