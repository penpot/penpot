#pragma once

#include <cstdint>
#include <string>

#include "common/api.h"

namespace lbug {
namespace common {

// Type used to represent time (microseconds)
struct LBUG_API dtime_t {
    int64_t micros;

    dtime_t();
    explicit dtime_t(int64_t micros_p);
    dtime_t& operator=(int64_t micros_p);

    // explicit conversion
    explicit operator int64_t() const;
    explicit operator double() const;

    // comparison operators
    bool operator==(const dtime_t& rhs) const;
    bool operator!=(const dtime_t& rhs) const;
    bool operator<=(const dtime_t& rhs) const;
    bool operator<(const dtime_t& rhs) const;
    bool operator>(const dtime_t& rhs) const;
    bool operator>=(const dtime_t& rhs) const;
};

// Note: Aside from some minor changes, this implementation is copied from DuckDB's source code:
// https://github.com/duckdb/duckdb/blob/master/src/include/duckdb/common/types/time.hpp.
// https://github.com/duckdb/duckdb/blob/master/src/common/types/time.cpp.
// For example, instead of using their idx_t type to refer to indices, we directly use uint64_t,
// which is the actual type of idx_t (so we say uint64_t len instead of idx_t len). When more
// functionality is needed, we should first consult these DuckDB links.
class Time {
public:
    // Convert a string in the format "hh:mm:ss" to a time object
    LBUG_API static dtime_t fromCString(const char* buf, uint64_t len);
    LBUG_API static bool tryConvertInterval(const char* buf, uint64_t len, uint64_t& pos,
        dtime_t& result);
    LBUG_API static bool tryConvertTime(const char* buf, uint64_t len, uint64_t& pos,
        dtime_t& result);

    // Convert a time object to a string in the format "hh:mm:ss"
    LBUG_API static std::string toString(dtime_t time);

    LBUG_API static dtime_t fromTime(int32_t hour, int32_t minute, int32_t second,
        int32_t microseconds = 0);

    // Extract the time from a given timestamp object
    LBUG_API static void convert(dtime_t time, int32_t& out_hour, int32_t& out_min,
        int32_t& out_sec, int32_t& out_micros);

    LBUG_API static bool isValid(int32_t hour, int32_t minute, int32_t second,
        int32_t milliseconds);

private:
    static bool tryConvertInternal(const char* buf, uint64_t len, uint64_t& pos, dtime_t& result);
    static dtime_t fromTimeInternal(int32_t hour, int32_t minute, int32_t second,
        int32_t microseconds = 0);
};

} // namespace common
} // namespace lbug
