#pragma once

#include <cstdint>
#include <memory>

#include "path_semantic.h"

namespace lbug {
namespace function {
class RJAlgorithm;
}

namespace common {

enum class QueryRelType : uint8_t {
    NON_RECURSIVE = 0,
    VARIABLE_LENGTH_WALK = 1,
    VARIABLE_LENGTH_TRAIL = 2,
    VARIABLE_LENGTH_ACYCLIC = 3,
    SHORTEST = 4,
    ALL_SHORTEST = 5,
    WEIGHTED_SHORTEST = 6,
    ALL_WEIGHTED_SHORTEST = 7,
};

struct QueryRelTypeUtils {
    static bool isRecursive(QueryRelType type) { return type != QueryRelType::NON_RECURSIVE; }

    static bool isWeighted(QueryRelType type) {
        return type == QueryRelType::WEIGHTED_SHORTEST ||
               type == QueryRelType::ALL_WEIGHTED_SHORTEST;
    }

    static PathSemantic getPathSemantic(QueryRelType queryRelType);

    static std::unique_ptr<function::RJAlgorithm> getFunction(QueryRelType type);
};

} // namespace common
} // namespace lbug
