#pragma once

#include <cstdint>
#include <string>

namespace lbug {
namespace common {

enum class ScanSourceType : uint8_t {
    EMPTY = 0,
    FILE = 1,
    OBJECT = 2,
    QUERY = 3,
    TABLE_FUNC = 4,
    PARAM = 5,
};

class ScanSourceTypeUtils {
public:
    static std::string toString(ScanSourceType type);
};

} // namespace common
} // namespace lbug
