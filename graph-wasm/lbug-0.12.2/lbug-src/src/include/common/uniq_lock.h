#pragma once

#include <mutex>

namespace lbug {
namespace common {

struct UniqLock {
    UniqLock() {}
    explicit UniqLock(std::mutex& mtx) : lck{mtx} {}

    UniqLock(const UniqLock&) = delete;
    UniqLock& operator=(const UniqLock&) = delete;

    UniqLock(UniqLock&& other) noexcept { std::swap(lck, other.lck); }
    UniqLock& operator=(UniqLock&& other) noexcept {
        std::swap(lck, other.lck);
        return *this;
    }
    bool isLocked() const { return lck.owns_lock(); }

private:
    std::unique_lock<std::mutex> lck;
};

} // namespace common
} // namespace lbug
