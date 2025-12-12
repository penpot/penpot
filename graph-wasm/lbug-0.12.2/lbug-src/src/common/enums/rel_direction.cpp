#include "common/enums/rel_direction.h"

#include <array>

#include "common/assert.h"

namespace lbug {
namespace common {

RelDataDirection RelDirectionUtils::getOppositeDirection(RelDataDirection direction) {
    static constexpr std::array oppositeDirections = {RelDataDirection::BWD, RelDataDirection::FWD};
    return oppositeDirections[relDirectionToKeyIdx(direction)];
}

std::string RelDirectionUtils::relDirectionToString(RelDataDirection direction) {
    static constexpr std::array directionStrs = {"fwd", "bwd"};
    return directionStrs[relDirectionToKeyIdx(direction)];
}

idx_t RelDirectionUtils::relDirectionToKeyIdx(RelDataDirection direction) {
    switch (direction) {
    case RelDataDirection::FWD:
        return 0;
    case RelDataDirection::BWD:
        return 1;
    default:
        KU_UNREACHABLE;
    }
}

table_id_t RelDirectionUtils::getNbrTableID(RelDataDirection direction, table_id_t srcTableID,
    table_id_t dstTableID) {
    switch (direction) {
    case RelDataDirection::FWD:
        return dstTableID;
    case RelDataDirection::BWD:
        return srcTableID;
    default:
        KU_UNREACHABLE;
    }
}

} // namespace common
} // namespace lbug
