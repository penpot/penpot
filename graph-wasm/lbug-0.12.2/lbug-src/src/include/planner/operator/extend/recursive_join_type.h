#pragma once

#include <cstdint>

namespace lbug {
namespace planner {

enum class RecursiveJoinType : uint8_t {
    TRACK_NONE = 0,
    TRACK_PATH = 1,
};

} // namespace planner
} // namespace lbug
