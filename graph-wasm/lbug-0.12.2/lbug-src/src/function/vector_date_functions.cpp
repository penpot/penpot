#include "function/date/vector_date_functions.h"

#include "function/date/date_functions.h"
#include "function/scalar_function.h"

using namespace lbug::common;

namespace lbug {
namespace function {

function_set DatePartFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::DATE},
        LogicalTypeID::INT64,
        ScalarFunction::BinaryExecFunction<ku_string_t, date_t, int64_t, DatePart>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::TIMESTAMP},
        LogicalTypeID::INT64,
        ScalarFunction::BinaryExecFunction<ku_string_t, timestamp_t, int64_t, DatePart>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::INTERVAL},
        LogicalTypeID::INT64,
        ScalarFunction::BinaryExecFunction<ku_string_t, interval_t, int64_t, DatePart>));
    return result;
}

function_set DateTruncFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::DATE}, LogicalTypeID::DATE,
        ScalarFunction::BinaryExecFunction<ku_string_t, date_t, date_t, DateTrunc>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::STRING, LogicalTypeID::TIMESTAMP},
        LogicalTypeID::TIMESTAMP,
        ScalarFunction::BinaryExecFunction<ku_string_t, timestamp_t, timestamp_t, DateTrunc>));
    return result;
}

function_set DayNameFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::DATE}, LogicalTypeID::STRING,
        ScalarFunction::UnaryExecFunction<date_t, ku_string_t, DayName>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::TIMESTAMP}, LogicalTypeID::STRING,
        ScalarFunction::UnaryExecFunction<timestamp_t, ku_string_t, DayName>));
    return result;
}

function_set GreatestFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::DATE, LogicalTypeID::DATE}, LogicalTypeID::DATE,
        ScalarFunction::BinaryExecFunction<date_t, date_t, date_t, Greatest>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::TIMESTAMP, LogicalTypeID::TIMESTAMP},
        LogicalTypeID::TIMESTAMP,
        ScalarFunction::BinaryExecFunction<timestamp_t, timestamp_t, timestamp_t, Greatest>));
    return result;
}

function_set LastDayFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::DATE},
            LogicalTypeID::DATE, ScalarFunction::UnaryExecFunction<date_t, date_t, LastDay>));
    result.push_back(
        make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{LogicalTypeID::TIMESTAMP},
            LogicalTypeID::DATE, ScalarFunction::UnaryExecFunction<timestamp_t, date_t, LastDay>));
    return result;
}

function_set LeastFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::DATE, LogicalTypeID::DATE}, LogicalTypeID::DATE,
        ScalarFunction::BinaryExecFunction<date_t, date_t, date_t, Least>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::TIMESTAMP, LogicalTypeID::TIMESTAMP},
        LogicalTypeID::TIMESTAMP,
        ScalarFunction::BinaryExecFunction<timestamp_t, timestamp_t, timestamp_t, Least>));
    return result;
}

function_set MakeDateFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::INT64, LogicalTypeID::INT64,
            LogicalTypeID::INT64},
        LogicalTypeID::DATE,
        ScalarFunction::TernaryExecFunction<int64_t, int64_t, int64_t, date_t, MakeDate>));
    return result;
}

function_set MonthNameFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::DATE}, LogicalTypeID::STRING,
        ScalarFunction::UnaryExecFunction<date_t, ku_string_t, MonthName>));
    result.push_back(make_unique<ScalarFunction>(name,
        std::vector<LogicalTypeID>{LogicalTypeID::TIMESTAMP}, LogicalTypeID::STRING,
        ScalarFunction::UnaryExecFunction<timestamp_t, ku_string_t, MonthName>));
    return result;
}

function_set CurrentDateFunction::getFunctionSet() {
    function_set result;
    result.push_back(make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{},
        LogicalTypeID::DATE, ScalarFunction::NullaryAuxilaryExecFunction<date_t, CurrentDate>));
    return result;
}

function_set CurrentTimestampFunction::getFunctionSet() {
    function_set result;
    result.push_back(
        make_unique<ScalarFunction>(name, std::vector<LogicalTypeID>{}, LogicalTypeID::TIMESTAMP,
            ScalarFunction::NullaryAuxilaryExecFunction<timestamp_tz_t, CurrentTimestamp>));
    return result;
}

} // namespace function
} // namespace lbug
