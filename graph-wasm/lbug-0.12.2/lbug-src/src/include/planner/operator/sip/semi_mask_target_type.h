#pragma once

#include <cstdint>

namespace lbug {
namespace planner {

enum class SemiMaskTargetType : uint8_t {
    SCAN_NODE = 0,
    RECURSIVE_EXTEND_INPUT_NODE = 2,
    RECURSIVE_EXTEND_OUTPUT_NODE = 3,
    RECURSIVE_EXTEND_PATH_NODE = 4,
    GDS_GRAPH_NODE = 5,
};

}
} // namespace lbug
