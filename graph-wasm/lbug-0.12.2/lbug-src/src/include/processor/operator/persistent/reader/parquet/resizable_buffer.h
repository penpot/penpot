#pragma once

#include <cstring>
#include <memory>
#include <stdexcept>

#include "common/utils.h"

namespace lbug {
namespace processor {

class ByteBuffer { // on to the 10 thousandth impl
public:
    ByteBuffer() = default;
    ByteBuffer(uint8_t* ptr, uint64_t len) : ptr{ptr}, len{len} {};

    uint8_t* ptr = nullptr;
    uint64_t len = 0;

public:
    void inc(uint64_t increment) {
        available(increment);
        len -= increment;
        ptr += increment;
    }

    template<class T>
    T read() {
        T val = get<T>();
        inc(sizeof(T));
        return val;
    }

    template<typename T>
    T Load(const uint8_t* ptr) {
        T ret{};
        memcpy(&ret, ptr, sizeof(ret));
        return ret;
    }

    template<class T>
    T get() {
        available(sizeof(T));
        T val = Load<T>(ptr);
        return val;
    }

    void copyTo(char* dest, uint64_t len) const {
        available(len);
        std::memcpy(dest, ptr, len);
    }

    // NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
    void zero() { std::memset(ptr, 0, len); }

    void available(uint64_t req_len) const {
        if (req_len > len) {
            throw std::runtime_error("Out of buffer");
        }
    }
};

class ResizeableBuffer : public ByteBuffer {
public:
    ResizeableBuffer() = default;
    explicit ResizeableBuffer(uint64_t new_size) { resize(new_size); }
    void resize(uint64_t new_size) {
        len = new_size;
        if (new_size == 0) {
            return;
        }
        if (new_size > allocLen) {
            allocLen = common::nextPowerOfTwo(new_size);
            allocatedData = std::make_unique<uint8_t[]>(allocLen);
            ptr = allocatedData.get();
        }
    }

private:
    std::unique_ptr<uint8_t[]> allocatedData;
    uint64_t allocLen = 0;
};

} // namespace processor
} // namespace lbug
