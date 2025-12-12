#pragma once

#include <cstdint>

#include "common/api.h"

namespace lbug {
namespace common {

class Value;

class NestedVal {
public:
    LBUG_API static uint32_t getChildrenSize(const Value* val);

    LBUG_API static Value* getChildVal(const Value* val, uint32_t idx);
};

} // namespace common
} // namespace lbug
