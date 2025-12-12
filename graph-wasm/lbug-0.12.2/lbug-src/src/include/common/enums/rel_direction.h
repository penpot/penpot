#pragma once

#include <cstdint>
#include <string>

#include "common/types/types.h"

namespace lbug {
namespace common {

enum class RelDataDirection : uint8_t { FWD = 0, BWD = 1, INVALID = 255 };
static constexpr idx_t NUM_REL_DIRECTIONS = 2;

struct RelDirectionUtils {
    static RelDataDirection getOppositeDirection(RelDataDirection direction);

    static std::string relDirectionToString(RelDataDirection direction);
    static idx_t relDirectionToKeyIdx(RelDataDirection direction);
    static table_id_t getNbrTableID(RelDataDirection direction, table_id_t srcTableID,
        table_id_t dstTableID);
};

} // namespace common
} // namespace lbug
