#pragma once

#include <cstdint>

namespace lbug {
namespace common {

enum class AlterType : uint8_t {
    RENAME = 0,

    ADD_PROPERTY = 10,
    DROP_PROPERTY = 11,
    RENAME_PROPERTY = 12,
    ADD_FROM_TO_CONNECTION = 13,
    DROP_FROM_TO_CONNECTION = 14,

    COMMENT = 201,
    INVALID = 255
};

} // namespace common
} // namespace lbug
