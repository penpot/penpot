#pragma once

#include <cstdint>
#include <string>

#include "common/api.h"

namespace lbug {

namespace regex {
class RE2;
}

namespace common {

struct timestamp_t;
struct date_t;

enum class DatePartSpecifier : uint8_t {
    YEAR,
    MONTH,
    DAY,
    DECADE,
    CENTURY,
    MILLENNIUM,
    QUARTER,
    MICROSECOND,
    MILLISECOND,
    SECOND,
    MINUTE,
    HOUR,
    WEEK,
};

struct LBUG_API interval_t {
    int32_t months = 0;
    int32_t days = 0;
    int64_t micros = 0;

    interval_t();
    interval_t(int32_t months_p, int32_t days_p, int64_t micros_p);

    // comparator operators
    bool operator==(const interval_t& rhs) const;
    bool operator!=(const interval_t& rhs) const;

    bool operator>(const interval_t& rhs) const;
    bool operator<=(const interval_t& rhs) const;
    bool operator<(const interval_t& rhs) const;
    bool operator>=(const interval_t& rhs) const;

    // arithmetic operators
    interval_t operator+(const interval_t& rhs) const;
    timestamp_t operator+(const timestamp_t& rhs) const;
    date_t operator+(const date_t& rhs) const;
    interval_t operator-(const interval_t& rhs) const;

    interval_t operator/(const uint64_t& rhs) const;
};

// Note: Aside from some minor changes, this implementation is copied from DuckDB's source code:
// https://github.com/duckdb/duckdb/blob/master/src/include/duckdb/common/types/interval.hpp.
// https://github.com/duckdb/duckdb/blob/master/src/common/types/interval.cpp.
// When more functionality is needed, we should first consult these DuckDB links.
// The Interval class is a static class that holds helper functions for the Interval type.
class Interval {
public:
    static constexpr const int32_t MONTHS_PER_MILLENIUM = 12000;
    static constexpr const int32_t MONTHS_PER_CENTURY = 1200;
    static constexpr const int32_t MONTHS_PER_DECADE = 120;
    static constexpr const int32_t MONTHS_PER_YEAR = 12;
    static constexpr const int32_t MONTHS_PER_QUARTER = 3;
    static constexpr const int32_t DAYS_PER_WEEK = 7;
    //! only used for interval comparison/ordering purposes, in which case a month counts as 30 days
    static constexpr const int64_t DAYS_PER_MONTH = 30;
    static constexpr const int64_t DAYS_PER_YEAR = 365;
    static constexpr const int64_t MSECS_PER_SEC = 1000;
    static constexpr const int32_t SECS_PER_MINUTE = 60;
    static constexpr const int32_t MINS_PER_HOUR = 60;
    static constexpr const int32_t HOURS_PER_DAY = 24;
    static constexpr const int32_t SECS_PER_HOUR = SECS_PER_MINUTE * MINS_PER_HOUR;
    static constexpr const int32_t SECS_PER_DAY = SECS_PER_HOUR * HOURS_PER_DAY;
    static constexpr const int32_t SECS_PER_WEEK = SECS_PER_DAY * DAYS_PER_WEEK;

    static constexpr const int64_t MICROS_PER_MSEC = 1000;
    static constexpr const int64_t MICROS_PER_SEC = MICROS_PER_MSEC * MSECS_PER_SEC;
    static constexpr const int64_t MICROS_PER_MINUTE = MICROS_PER_SEC * SECS_PER_MINUTE;
    static constexpr const int64_t MICROS_PER_HOUR = MICROS_PER_MINUTE * MINS_PER_HOUR;
    static constexpr const int64_t MICROS_PER_DAY = MICROS_PER_HOUR * HOURS_PER_DAY;
    static constexpr const int64_t MICROS_PER_WEEK = MICROS_PER_DAY * DAYS_PER_WEEK;
    static constexpr const int64_t MICROS_PER_MONTH = MICROS_PER_DAY * DAYS_PER_MONTH;

    static constexpr const int64_t NANOS_PER_MICRO = 1000;
    static constexpr const int64_t NANOS_PER_MSEC = NANOS_PER_MICRO * MICROS_PER_MSEC;
    static constexpr const int64_t NANOS_PER_SEC = NANOS_PER_MSEC * MSECS_PER_SEC;
    static constexpr const int64_t NANOS_PER_MINUTE = NANOS_PER_SEC * SECS_PER_MINUTE;
    static constexpr const int64_t NANOS_PER_HOUR = NANOS_PER_MINUTE * MINS_PER_HOUR;
    static constexpr const int64_t NANOS_PER_DAY = NANOS_PER_HOUR * HOURS_PER_DAY;
    static constexpr const int64_t NANOS_PER_WEEK = NANOS_PER_DAY * DAYS_PER_WEEK;

    LBUG_API static void addition(interval_t& result, uint64_t number, std::string specifierStr);
    LBUG_API static interval_t fromCString(const char* str, uint64_t len);
    LBUG_API static std::string toString(interval_t interval);
    LBUG_API static bool greaterThan(const interval_t& left, const interval_t& right);
    LBUG_API static void normalizeIntervalEntries(interval_t input, int64_t& months, int64_t& days,
        int64_t& micros);
    LBUG_API static void tryGetDatePartSpecifier(std::string specifier, DatePartSpecifier& result);
    LBUG_API static int32_t getIntervalPart(DatePartSpecifier specifier, interval_t timestamp);
    LBUG_API static int64_t getMicro(const interval_t& val);
    LBUG_API static int64_t getNanoseconds(const interval_t& val);
    LBUG_API static const regex::RE2& regexPattern1();
    LBUG_API static const regex::RE2& regexPattern2();
};

} // namespace common
} // namespace lbug
