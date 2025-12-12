#pragma once

#include "function/function.h"

namespace lbug {
namespace function {

struct DatePartFunction {
    static constexpr const char* name = "DATE_PART";

    static function_set getFunctionSet();
};

struct DatePartFunctionAlias {
    using alias = DatePartFunction;

    static constexpr const char* name = "DATEPART";
};

struct DateTruncFunction {
    static constexpr const char* name = "DATE_TRUNC";

    static function_set getFunctionSet();
};

struct DateTruncFunctionAlias {
    using alias = DateTruncFunction;

    static constexpr const char* name = "DATETRUNC";
};

struct DayNameFunction {
    static constexpr const char* name = "DAYNAME";

    static function_set getFunctionSet();
};

struct GreatestFunction {
    static constexpr const char* name = "GREATEST";

    static function_set getFunctionSet();
};

struct LastDayFunction {
    static constexpr const char* name = "LAST_DAY";

    static function_set getFunctionSet();
};

struct LeastFunction {
    static constexpr const char* name = "LEAST";

    static function_set getFunctionSet();
};

struct MakeDateFunction {
    static constexpr const char* name = "MAKE_DATE";

    static function_set getFunctionSet();
};

struct MonthNameFunction {
    static constexpr const char* name = "MONTHNAME";

    static function_set getFunctionSet();
};

struct CurrentDateFunction {
    static constexpr const char* name = "CURRENT_DATE";

    static function_set getFunctionSet();
};

struct CurrentTimestampFunction {
    static constexpr const char* name = "CURRENT_TIMESTAMP";

    static function_set getFunctionSet();
};

} // namespace function
} // namespace lbug
