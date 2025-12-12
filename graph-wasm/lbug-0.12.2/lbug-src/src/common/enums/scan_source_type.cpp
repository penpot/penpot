#include "common/enums/scan_source_type.h"

#include "common/assert.h"

namespace lbug {
namespace common {

std::string ScanSourceTypeUtils::toString(ScanSourceType type) {
    switch (type) {
    case ScanSourceType::EMPTY: {
        return "EMPTY";
    }
    case ScanSourceType::FILE: {
        return "FILE";
    }
    case ScanSourceType::OBJECT: {
        return "OBJECT";
    }
    case ScanSourceType::QUERY: {
        return "QUERY";
    }
    default:
        KU_UNREACHABLE;
    }
}

} // namespace common
} // namespace lbug
