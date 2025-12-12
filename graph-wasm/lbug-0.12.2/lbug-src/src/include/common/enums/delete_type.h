#pragma once

#include <cstdint>

namespace lbug {
namespace common {

enum class DeleteNodeType : uint8_t {
    DELETE = 0,
    DETACH_DELETE = 1,
};

} // namespace common
} // namespace lbug
