#pragma once

#include <cstdint>
#include <string>

#include "common/api.h"

namespace lbug {
namespace common {

enum class TableType : uint8_t {
    UNKNOWN = 0,
    NODE = 1,
    REL = 2,
    FOREIGN = 5,
};

struct LBUG_API TableTypeUtils {
    static std::string toString(TableType tableType);
};

} // namespace common
} // namespace lbug
