#include "common/types/dtime_t.h"

#include <memory>

#include "common/assert.h"
#include "common/exception/conversion.h"
#include "common/string_format.h"
#include "common/types/cast_helpers.h"
#include "common/types/date_t.h"

namespace lbug {
namespace common {

static_assert(sizeof(dtime_t) == sizeof(int64_t), "dtime_t was padded");

dtime_t::dtime_t() : micros(0) {}

dtime_t::dtime_t(int64_t micros_p) : micros(micros_p) {}

dtime_t& dtime_t::operator=(int64_t micros_p) {
    micros = micros_p;
    return *this;
}

dtime_t::operator int64_t() const {
    return micros;
}

dtime_t::operator double() const {
    return micros;
}

bool dtime_t::operator==(const dtime_t& rhs) const {
    return micros == rhs.micros;
}

bool dtime_t::operator!=(const dtime_t& rhs) const {
    return micros != rhs.micros;
}

bool dtime_t::operator<=(const dtime_t& rhs) const {
    return micros <= rhs.micros;
}

bool dtime_t::operator<(const dtime_t& rhs) const {
    return micros < rhs.micros;
}

bool dtime_t::operator>(const dtime_t& rhs) const {
    return micros > rhs.micros;
}

bool dtime_t::operator>=(const dtime_t& rhs) const {
    return micros >= rhs.micros;
}

bool Time::tryConvertInternal(const char* buf, uint64_t len, uint64_t& pos, dtime_t& result) {
    int32_t hour = -1, min = -1, sec = -1, micros = -1;
    pos = 0;

    if (len == 0) {
        return false;
    }

    // skip leading spaces
    while (pos < len && isspace(buf[pos])) {
        pos++;
    }

    if (pos >= len) {
        return false;
    }

    if (!isdigit(buf[pos])) {
        return false;
    }

    // Allow up to 9 digit hours to support intervals
    hour = 0;
    for (int32_t digits = 9; pos < len && isdigit(buf[pos]); ++pos) {
        if (digits-- > 0) {
            hour = hour * 10 + (buf[pos] - '0');
        } else {
            return false;
        }
    }

    if (pos >= len) {
        return false;
    }

    // fetch the separator
    char sep = buf[pos++];
    if (sep != ':') {
        // invalid separator
        return false;
    }

    if (!Date::parseDoubleDigit(buf, len, pos, min)) {
        return false;
    }
    if (min < 0 || min >= 60) {
        return false;
    }

    if (pos >= len) {
        return false;
    }

    if (buf[pos++] != sep) {
        return false;
    }

    if (!Date::parseDoubleDigit(buf, len, pos, sec)) {
        return false;
    }
    if (sec < 0 || sec >= 60) {
        return false;
    }

    micros = 0;
    if (pos < len && buf[pos] == '.') {
        pos++;
        // we expect some microseconds
        int32_t mult = 100000;
        for (; pos < len && isdigit(buf[pos]); pos++, mult /= 10) {
            if (mult > 0) {
                micros += (buf[pos] - '0') * mult;
            }
        }
    }

    result = Time::fromTimeInternal(hour, min, sec, micros);
    return true;
}

bool Time::tryConvertInterval(const char* buf, uint64_t len, uint64_t& pos, dtime_t& result) {
    if (!Time::tryConvertInternal(buf, len, pos, result)) {
        return false;
    }
    // check remaining string for non-space characters
    // skip trailing spaces
    while (pos < len && isspace(buf[pos])) {
        pos++;
    }
    // check position. if end was not reached, non-space chars remaining
    if (pos < len) {
        return false;
    }
    return true;
}

// string format is hh:mm:ss[.mmmmmm] (ISO 8601) (m represent microseconds)
// microseconds is optional, timezone is currently not supported
bool Time::tryConvertTime(const char* buf, uint64_t len, uint64_t& pos, dtime_t& result) {
    if (!Time::tryConvertInternal(buf, len, pos, result)) {
        return false;
    }
    return result.micros < Interval::MICROS_PER_DAY;
}

dtime_t Time::fromCString(const char* buf, uint64_t len) {
    dtime_t result;
    uint64_t pos = 0;
    if (!Time::tryConvertTime(buf, len, pos, result)) {
        throw ConversionException(stringFormat("Error occurred during parsing time. Given: \"{}\". "
                                               "Expected format: (hh:mm:ss[.zzzzzz]).",
            std::string(buf, len)));
    }
    return result;
}

std::string Time::toString(dtime_t time) {
    int32_t time_units[4];
    Time::convert(time, time_units[0], time_units[1], time_units[2], time_units[3]);

    char micro_buffer[6];
    auto length = TimeToStringCast::Length(time_units, micro_buffer);
    auto buffer = std::unique_ptr<char[]>(new char[length]);
    TimeToStringCast::Format(buffer.get(), length, time_units, micro_buffer);
    return std::string(buffer.get(), length);
}

bool Time::isValid(int32_t hour, int32_t minute, int32_t second, int32_t microseconds) {
    if (hour > 23 || hour < 0 || minute > 59 || minute < 0 || second > 59 || second < 0 ||
        microseconds > 999999 || microseconds < 0) {
        return false;
    }
    return true;
}

dtime_t Time::fromTimeInternal(int32_t hour, int32_t minute, int32_t second, int32_t microseconds) {
    int64_t result = 0;
    result = hour;                                             // hours
    result = result * Interval::MINS_PER_HOUR + minute;        // hours -> minutes
    result = result * Interval::SECS_PER_MINUTE + second;      // minutes -> seconds
    result = result * Interval::MICROS_PER_SEC + microseconds; // seconds -> microseconds
    return dtime_t(result);
}

dtime_t Time::fromTime(int32_t hour, int32_t minute, int32_t second, int32_t microseconds) {
    if (!Time::isValid(hour, minute, second, microseconds)) {
        throw ConversionException(stringFormat("Time field value out of range: {}:{}:{}[.{}].",
            hour, minute, second, microseconds));
    }
    return Time::fromTimeInternal(hour, minute, second, microseconds);
}

void Time::convert(dtime_t dtime, int32_t& hour, int32_t& min, int32_t& sec, int32_t& micros) {
    int64_t time = dtime.micros;
    hour = int32_t(time / Interval::MICROS_PER_HOUR);
    time -= int64_t(hour) * Interval::MICROS_PER_HOUR;
    min = int32_t(time / Interval::MICROS_PER_MINUTE);
    time -= int64_t(min) * Interval::MICROS_PER_MINUTE;
    sec = int32_t(time / Interval::MICROS_PER_SEC);
    time -= int64_t(sec) * Interval::MICROS_PER_SEC;
    micros = int32_t(time);
    KU_ASSERT(Time::isValid(hour, min, sec, micros));
}

} // namespace common
} // namespace lbug
