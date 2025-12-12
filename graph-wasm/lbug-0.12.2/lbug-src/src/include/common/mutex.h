#pragma once

#include <mutex>
#include <optional>

#include "common/copy_constructors.h"

namespace lbug {
namespace common {

template<typename T>
class MutexGuard {
    template<typename T2>
    friend class Mutex;
    MutexGuard(T& data, std::unique_lock<std::mutex> lck) : data(&data), lck(std::move(lck)) {}

public:
    DELETE_COPY_DEFAULT_MOVE(MutexGuard);

    T* operator->() & { return data; }
    T& operator*() & { return *data; }
    T* get() & { return data; }

    // Must not call these operators on a temporary MutexGuard!
    // Guards _must_ be held while accessing the inner data.
    T* operator->() && = delete;
    T& operator*() && = delete;
    T* get() && = delete;

private:
    T* data;
    std::unique_lock<std::mutex> lck;
};

template<typename T>
class Mutex {
public:
    Mutex() : data() {}
    explicit Mutex(T data) : data(std::move(data)) {}
    DELETE_COPY_AND_MOVE(Mutex);

    MutexGuard<T> lock() {
        std::unique_lock lck{mtx};
        return MutexGuard(data, std::move(lck));
    }

    std::optional<MutexGuard<T>> try_lock() {
        if (!mtx.try_lock()) {
            return std::nullopt;
        }
        std::unique_lock lck{mtx, std::adopt_lock};
        return MutexGuard(data, std::move(lck));
    }

private:
    T data;
    std::mutex mtx;
};

} // namespace common
} // namespace lbug
