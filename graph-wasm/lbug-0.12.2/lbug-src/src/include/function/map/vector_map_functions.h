#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct MapCreationFunctions {
    static constexpr const char* name = "MAP";

    static function_set getFunctionSet();
};

struct MapExtractFunctions {
    static constexpr const char* name = "MAP_EXTRACT";

    static function_set getFunctionSet();
};

struct ElementAtFunctions {
    using alias = MapExtractFunctions;

    static constexpr const char* name = "ELEMENT_AT";
};

struct MapKeysFunctions {
    static constexpr const char* name = "MAP_KEYS";

    static function_set getFunctionSet();
};

struct MapValuesFunctions {
    static constexpr const char* name = "MAP_VALUES";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
