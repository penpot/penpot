#pragma once

#include <cstdint>

#include "common/cast.h"

namespace lbug {
namespace common {

class Reader {
public:
    virtual void read(uint8_t* data, uint64_t size) = 0;
    virtual ~Reader() = default;

    virtual bool finished() = 0;
    virtual void onObjectBegin() {};
    virtual void onObjectEnd() {};

    template<typename TARGET>
    TARGET* cast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }
};

} // namespace common
} // namespace lbug
