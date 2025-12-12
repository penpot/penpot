#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct ClearWarningsFunction {
    static constexpr const char* name = "CLEAR_WARNINGS";

    static function_set getFunctionSet();
};

struct ProjectGraphNativeFunction {
    static constexpr const char* name = "PROJECT_GRAPH";

    static function_set getFunctionSet();
};

struct ProjectGraphCypherFunction {
    static constexpr const char* name = "PROJECT_GRAPH_CYPHER";

    static function_set getFunctionSet();
};

struct DropProjectedGraphFunction {
    static constexpr const char* name = "DROP_PROJECTED_GRAPH";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
