#pragma once

#include <cstdint>
#include <string>

namespace lbug {
namespace common {

enum class ConflictAction : uint8_t {
    ON_CONFLICT_THROW = 0,
    ON_CONFLICT_DO_NOTHING = 1,
    INVALID = 255,
};

struct ConflictActionUtil {
    static std::string toString(ConflictAction conflictAction);
};

} // namespace common
} // namespace lbug
