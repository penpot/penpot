#include "common/types/timestamp_t.h"

#include <chrono>
#include <string>

#include "common/exception/conversion.h"
#include "common/string_format.h"
#include "function/arithmetic/multiply.h"

namespace lbug {
namespace common {

timestamp_t::timestamp_t() : value(0) {}

timestamp_t::timestamp_t(int64_t value_p) : value(value_p) {}

timestamp_t& timestamp_t::operator=(int64_t value_p) {
    value = value_p;
    return *this;
}

timestamp_t::operator int64_t() const {
    return value;
}

bool timestamp_t::operator==(const timestamp_t& rhs) const {
    return value == rhs.value;
}

bool timestamp_t::operator!=(const timestamp_t& rhs) const {
    return value != rhs.value;
}

bool timestamp_t::operator<=(const timestamp_t& rhs) const {
    return value <= rhs.value;
}

bool timestamp_t::operator<(const timestamp_t& rhs) const {
    return value < rhs.value;
}

bool timestamp_t::operator>(const timestamp_t& rhs) const {
    return value > rhs.value;
}

bool timestamp_t::operator>=(const timestamp_t& rhs) const {
    return value >= rhs.value;
}

bool timestamp_t::operator==(const date_t& rhs) const {
    return rhs == *this;
}

bool timestamp_t::operator!=(const date_t& rhs) const {
    return !(rhs == *this);
}

bool timestamp_t::operator<(const date_t& rhs) const {
    return rhs > *this;
}

bool timestamp_t::operator<=(const date_t& rhs) const {
    return rhs >= *this;
}

bool timestamp_t::operator>(const date_t& rhs) const {
    return rhs < *this;
}

bool timestamp_t::operator>=(const date_t& rhs) const {
    return rhs <= *this;
}

timestamp_t timestamp_t::operator+(const interval_t& interval) const {
    date_t date{};
    date_t result_date{};
    dtime_t time{};
    Timestamp::convert(*this, date, time);
    result_date = date + interval;
    date = result_date;
    int64_t diff =
        interval.micros - ((interval.micros / Interval::MICROS_PER_DAY) * Interval::MICROS_PER_DAY);
    time.micros += diff;
    if (time.micros >= Interval::MICROS_PER_DAY) {
        time.micros -= Interval::MICROS_PER_DAY;
        date.days++;
    } else if (time.micros < 0) {
        time.micros += Interval::MICROS_PER_DAY;
        date.days--;
    }
    return Timestamp::fromDateTime(date, time);
}

timestamp_t timestamp_t::operator-(const interval_t& interval) const {
    interval_t inverseRight{};
    inverseRight.months = -interval.months;
    inverseRight.days = -interval.days;
    inverseRight.micros = -interval.micros;
    return (*this) + inverseRight;
}

interval_t timestamp_t::operator-(const timestamp_t& rhs) const {
    interval_t result{};
    uint64_t diff = std::abs(value - rhs.value);
    result.months = 0;
    result.days = diff / Interval::MICROS_PER_DAY;
    result.micros = diff % Interval::MICROS_PER_DAY;
    if (value < rhs.value) {
        result.days = -result.days;
        result.micros = -result.micros;
    }
    return result;
}

static_assert(sizeof(timestamp_t) == sizeof(int64_t), "timestamp_t was padded");

bool Timestamp::tryConvertTimestamp(const char* str, uint64_t len, timestamp_t& result) {
    uint64_t pos = 0;
    date_t date;
    dtime_t time;

    if (!Date::tryConvertDate(str, len, pos, date, true /*allowTrailing*/)) {
        return false;
    }
    if (pos == len) {
        // no time: only a date
        result = fromDateTime(date, dtime_t(0));
        return true;
    }
    // try to parse a time field
    if (str[pos] == ' ' || str[pos] == 'T') {
        pos++;
    }
    uint64_t time_pos = 0;
    if (!Time::tryConvertTime(str + pos, len - pos, time_pos, time)) {
        return false;
    }
    pos += time_pos;
    result = fromDateTime(date, time);
    if (pos < len) {
        // skip a "Z" at the end (as per the ISO8601 specs)
        if (str[pos] == 'Z') {
            pos++;
        }
        int hour_offset = 0, minute_offset = 0;
        if (Timestamp::tryParseUTCOffset(str, pos, len, hour_offset, minute_offset)) {
            result.value -= hour_offset * Interval::MICROS_PER_HOUR +
                            minute_offset * Interval::MICROS_PER_MINUTE;
        }
        // skip any spaces at the end
        while (pos < len && isspace(str[pos])) {
            pos++;
        }
        if (pos < len) {
            return false;
        }
    }
    return true;
}

// string format is YYYY-MM-DDThh:mm:ss[.mmmmmm]
// T may be a space, timezone is not supported yet
// ISO 8601
timestamp_t Timestamp::fromCString(const char* str, uint64_t len) {
    timestamp_t result;
    if (!tryConvertTimestamp(str, len, result)) {
        throw ConversionException(getTimestampConversionExceptionMsg(str, len));
    }
    return result;
}

bool Timestamp::tryParseUTCOffset(const char* str, uint64_t& pos, uint64_t len, int& hour_offset,
    int& minute_offset) {
    minute_offset = 0;
    uint64_t curpos = pos;
    // parse the next 3 characters
    if (curpos + 3 > len) {
        // no characters left to parse
        return false;
    }
    char sign_char = str[curpos];
    if (sign_char != '+' && sign_char != '-') {
        // expected either + or -
        return false;
    }
    curpos++;
    if (!isdigit(str[curpos]) || !isdigit(str[curpos + 1])) {
        // expected +HH or -HH
        return false;
    }
    hour_offset = (str[curpos] - '0') * 10 + (str[curpos + 1] - '0');
    if (sign_char == '-') {
        hour_offset = -hour_offset;
    }
    curpos += 2;

    // optional minute specifier: expected either "MM" or ":MM"
    if (curpos >= len) {
        // done, nothing left
        pos = curpos;
        return true;
    }
    if (str[curpos] == ':') {
        curpos++;
    }
    if (curpos + 2 > len || !isdigit(str[curpos]) || !isdigit(str[curpos + 1])) {
        // no MM specifier
        pos = curpos;
        return true;
    }
    // we have an MM specifier: parse it
    minute_offset = (str[curpos] - '0') * 10 + (str[curpos + 1] - '0');
    if (sign_char == '-') {
        minute_offset = -minute_offset;
    }
    pos = curpos + 2;
    return true;
}

std::string Timestamp::toString(timestamp_t timestamp) {
    date_t date;
    dtime_t time;
    Timestamp::convert(timestamp, date, time);
    return Date::toString(date) + " " + Time::toString(time);
}

// Date header is in the format: %Y%m%d.
std::string Timestamp::getDateHeader(const timestamp_t& timestamp) {
    auto date = Timestamp::getDate(timestamp);
    int32_t year = 0, month = 0, day = 0;
    std::string yearStr, monthStr, dayStr;
    Date::convert(date, year, month, day);
    yearStr = std::to_string(year);
    monthStr = std::to_string(month);
    dayStr = std::to_string(day);
    if (month < 10) {
        monthStr = "0" + monthStr;
    }
    if (day < 10) {
        dayStr = "0" + dayStr;
    }
    return stringFormat("{}{}{}", yearStr, monthStr, dayStr);
}

// Timestamp header is in the format: %Y%m%dT%H%M%SZ.
std::string Timestamp::getDateTimeHeader(const timestamp_t& timestamp) {
    auto dateHeader = getDateHeader(timestamp);
    auto time = Timestamp::getTime(timestamp);
    int32_t hours = 0, minutes = 0, seconds = 0, micros = 0;
    std::string hoursStr, minutesStr, secondsStr;
    Time::convert(time, hours, minutes, seconds, micros);
    hoursStr = std::to_string(hours);
    minutesStr = std::to_string(minutes);
    secondsStr = std::to_string(seconds);

    if (hours < 10) {
        hoursStr = "0" + hoursStr;
    }
    if (minutes < 10) {
        minutesStr = "0" + minutesStr;
    }
    if (seconds < 10) {
        secondsStr = "0" + secondsStr;
    }
    return stringFormat("{}T{}{}{}Z", dateHeader, hoursStr, minutesStr, secondsStr);
}

date_t Timestamp::getDate(timestamp_t timestamp) {
    return date_t((timestamp.value + (timestamp.value < 0)) / Interval::MICROS_PER_DAY -
                  (timestamp.value < 0));
}

dtime_t Timestamp::getTime(timestamp_t timestamp) {
    date_t date = Timestamp::getDate(timestamp);
    return dtime_t(timestamp.value - (int64_t(date.days) * int64_t(Interval::MICROS_PER_DAY)));
}

timestamp_t Timestamp::fromDateTime(date_t date, dtime_t time) {
    timestamp_t result;
    int32_t year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0, microsecond = -1;
    Date::convert(date, year, month, day);
    Time::convert(time, hour, minute, second, microsecond);
    result.value = date.days * Interval::MICROS_PER_DAY + time.micros;
    return result;
}

void Timestamp::convert(timestamp_t timestamp, date_t& out_date, dtime_t& out_time) {
    out_date = getDate(timestamp);
    out_time = getTime(timestamp);
}

timestamp_t Timestamp::fromEpochMicroSeconds(int64_t micros) {
    return timestamp_t(micros);
}

timestamp_t Timestamp::fromEpochMilliSeconds(int64_t ms) {
    int64_t microSeconds = 0;
    function::Multiply::operation(ms, Interval::MICROS_PER_MSEC, microSeconds);
    return fromEpochMicroSeconds(microSeconds);
}

// LCOV_EXCL_START
// TODO(Kebing): will add the tests in the timestamp PR
timestamp_t Timestamp::fromEpochSeconds(int64_t sec) {
    int64_t microSeconds = 0;
    function::Multiply::operation(sec, Interval::MICROS_PER_SEC, microSeconds);
    return fromEpochMicroSeconds(microSeconds);
}
// LCOV_EXCL_STOP

timestamp_t Timestamp::fromEpochNanoSeconds(int64_t ns) {
    return fromEpochMicroSeconds(ns / 1000);
}

int32_t Timestamp::getTimestampPart(DatePartSpecifier specifier, timestamp_t timestamp) {
    switch (specifier) {
    case DatePartSpecifier::MICROSECOND:
        return getTime(timestamp).micros % Interval::MICROS_PER_MINUTE;
    case DatePartSpecifier::MILLISECOND:
        return getTimestampPart(DatePartSpecifier::MICROSECOND, timestamp) /
               Interval::MICROS_PER_MSEC;
    case DatePartSpecifier::SECOND:
        return getTimestampPart(DatePartSpecifier::MICROSECOND, timestamp) /
               Interval::MICROS_PER_SEC;
    case DatePartSpecifier::MINUTE:
        return (getTime(timestamp).micros % Interval::MICROS_PER_HOUR) /
               Interval::MICROS_PER_MINUTE;
    case DatePartSpecifier::HOUR:
        return getTime(timestamp).micros / Interval::MICROS_PER_HOUR;
    default:
        date_t date = getDate(timestamp);
        return Date::getDatePart(specifier, date);
    }
}

timestamp_t Timestamp::trunc(DatePartSpecifier specifier, timestamp_t timestamp) {
    int32_t hour = 0, min = 0, sec = 0, micros = 0;
    date_t date;
    dtime_t time;
    Timestamp::convert(timestamp, date, time);
    Time::convert(time, hour, min, sec, micros);
    switch (specifier) {
    case DatePartSpecifier::MICROSECOND:
        return timestamp;
    case DatePartSpecifier::MILLISECOND:
        micros -= micros % Interval::MICROS_PER_MSEC;
        return Timestamp::fromDateTime(date, Time::fromTime(hour, min, sec, micros));
    case DatePartSpecifier::SECOND:
        return Timestamp::fromDateTime(date, Time::fromTime(hour, min, sec, 0 /* microseconds */));
    case DatePartSpecifier::MINUTE:
        return Timestamp::fromDateTime(date,
            Time::fromTime(hour, min, 0 /* seconds */, 0 /* microseconds */));
    case DatePartSpecifier::HOUR:
        return Timestamp::fromDateTime(date,
            Time::fromTime(hour, 0 /* minutes */, 0 /* seconds */, 0 /* microseconds */));
    default:
        date = getDate(timestamp);
        return fromDateTime(Date::trunc(specifier, date), dtime_t(0));
    }
}

int64_t Timestamp::getEpochNanoSeconds(const timestamp_t& timestamp) {
    int64_t result = 0;
    function::Multiply::operation(timestamp.value, Interval::NANOS_PER_MICRO, result);
    return result;
}

int64_t Timestamp::getEpochMilliSeconds(const timestamp_t& timestamp) {
    return timestamp.value / Interval::MICROS_PER_MSEC;
}

int64_t Timestamp::getEpochSeconds(const timestamp_t& timestamp) {
    return timestamp.value / Interval::MICROS_PER_SEC;
}

timestamp_t Timestamp::getCurrentTimestamp() {
    auto now = std::chrono::system_clock::now();
    return Timestamp::fromEpochMilliSeconds(
        duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count());
}

} // namespace common
} // namespace lbug
