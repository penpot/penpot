#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct RewriteFunctionBindInput;

struct OffsetFunction {
    static constexpr const char* name = "OFFSET";

    static function_set getFunctionSet();
};

struct IDFunction {
    static constexpr const char* name = "ID";

    static function_set getFunctionSet();
};

struct StartNodeFunction {
    static constexpr const char* name = "START_NODE";

    static function_set getFunctionSet();
};

struct EndNodeFunction {
    static constexpr const char* name = "END_NODE";

    static function_set getFunctionSet();
};

struct LabelFunction {
    static constexpr const char* name = "LABEL";

    static function_set getFunctionSet();
    static std::shared_ptr<binder::Expression> rewriteFunc(const RewriteFunctionBindInput& input);
};

struct LabelsFunction {
    using alias = LabelFunction;

    static constexpr const char* name = "LABELS";
};

struct CostFunction {
    static constexpr const char* name = "COST";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
