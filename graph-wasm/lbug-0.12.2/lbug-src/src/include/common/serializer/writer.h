#pragma once

#include <cstdint>

namespace lbug {
namespace common {

class Writer {
public:
    virtual void write(const uint8_t* data, uint64_t size) = 0;
    virtual ~Writer() = default;

    virtual uint64_t getSize() const = 0;

    virtual void clear() = 0;
    virtual void flush() = 0;
    virtual void sync() = 0;
    virtual void onObjectBegin() {};
    virtual void onObjectEnd() {};

    template<class TARGET>
    const TARGET& cast() const {
        return dynamic_cast<const TARGET&>(*this);
    }
    template<class TARGET>
    TARGET& cast() {
        return dynamic_cast<TARGET&>(*this);
    }
};

} // namespace common
} // namespace lbug
