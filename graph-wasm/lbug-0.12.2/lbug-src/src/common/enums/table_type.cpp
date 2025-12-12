#include "common/enums/table_type.h"

#include "common/assert.h"

namespace lbug {
namespace common {

std::string TableTypeUtils::toString(TableType tableType) {
    switch (tableType) {
    case TableType::UNKNOWN: {
        return "UNKNOWN";
    }
    case TableType::NODE: {
        return "NODE";
    }
    case TableType::REL: {
        return "REL";
    }
    case TableType::FOREIGN: {
        return "ATTACHED";
    }
    default:
        KU_UNREACHABLE;
    }
}

} // namespace common
} // namespace lbug
