#pragma once

#include "common/assert.h"
#include "common/enums/extend_direction.h"
#include "common/enums/rel_direction.h"

namespace lbug {
namespace common {

class ExtendDirectionUtil {
public:
    static RelDataDirection getRelDataDirection(ExtendDirection direction) {
        KU_ASSERT(direction != ExtendDirection::BOTH);
        return direction == ExtendDirection::FWD ? RelDataDirection::FWD : RelDataDirection::BWD;
    }

    static ExtendDirection fromString(const std::string& str);
    static std::string toString(ExtendDirection direction);
};

} // namespace common
} // namespace lbug
