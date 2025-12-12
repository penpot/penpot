#pragma once

#include "function/scalar_function.h"
#include "interval_functions.h"

namespace lbug {
namespace function {

struct IntervalFunction {
public:
    template<class OPERATION>
    static function_set getUnaryIntervalFunction(std::string funcName) {
        function_set result;
        result.push_back(std::make_unique<ScalarFunction>(funcName,
            std::vector<common::LogicalTypeID>{common::LogicalTypeID::INT64},
            common::LogicalTypeID::INTERVAL,
            ScalarFunction::UnaryExecFunction<int64_t, common::interval_t, OPERATION>));
        return result;
    }
};

struct ToYearsFunction {
    static constexpr const char* name = "TO_YEARS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToYears>(name);
    }
};

struct ToMonthsFunction {
    static constexpr const char* name = "TO_MONTHS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToMonths>(name);
    }
};

struct ToDaysFunction {
    static constexpr const char* name = "TO_DAYS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToDays>(name);
    }
};

struct ToHoursFunction {
    static constexpr const char* name = "TO_HOURS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToHours>(name);
    }
};

struct ToMinutesFunction {
    static constexpr const char* name = "TO_MINUTES";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToMinutes>(name);
    }
};

struct ToSecondsFunction {
    static constexpr const char* name = "TO_SECONDS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToSeconds>(name);
    }
};

struct ToMillisecondsFunction {
    static constexpr const char* name = "TO_MILLISECONDS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToMilliseconds>(name);
    }
};

struct ToMicrosecondsFunction {
    static constexpr const char* name = "TO_MICROSECONDS";

    static function_set getFunctionSet() {
        return IntervalFunction::getUnaryIntervalFunction<ToMicroseconds>(name);
    }
};

} // namespace function
} // namespace lbug
