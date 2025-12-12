#include "common/types/interval_t.h"

#include "common/assert.h"
#include "common/exception/conversion.h"
#include "common/exception/overflow.h"
#include "common/string_utils.h"
#include "common/types/cast_helpers.h"
#include "common/types/timestamp_t.h"
#include "function/arithmetic/add.h"
#include "function/arithmetic/multiply.h"
#include "function/cast/functions/cast_from_string_functions.h"
#include "function/cast/functions/cast_functions.h"
#include "re2.h"

namespace lbug {
namespace common {

interval_t::interval_t() = default;

interval_t::interval_t(int32_t months_p, int32_t days_p, int64_t micros_p)
    : months(months_p), days(days_p), micros(micros_p) {}

bool interval_t::operator==(const interval_t& rhs) const {
    return this->days == rhs.days && this->months == rhs.months && this->micros == rhs.micros;
}

bool interval_t::operator!=(const interval_t& rhs) const {
    return !(*this == rhs);
}

bool interval_t::operator>(const interval_t& rhs) const {
    return Interval::greaterThan(*this, rhs);
}

bool interval_t::operator<=(const interval_t& rhs) const {
    return !(*this > rhs);
}

bool interval_t::operator<(const interval_t& rhs) const {
    return !(*this >= rhs);
}

bool interval_t::operator>=(const interval_t& rhs) const {
    return *this > rhs || *this == rhs;
}

interval_t interval_t::operator+(const interval_t& rhs) const {
    interval_t result{};
    result.months = months + rhs.months;
    result.days = days + rhs.days;
    result.micros = micros + rhs.micros;
    return result;
}

timestamp_t interval_t::operator+(const timestamp_t& rhs) const {
    return rhs + *this;
}

date_t interval_t::operator+(const date_t& rhs) const {
    return rhs + *this;
}

interval_t interval_t::operator-(const interval_t& rhs) const {
    interval_t result{};
    result.months = months - rhs.months;
    result.days = days - rhs.days;
    result.micros = micros - rhs.micros;
    return result;
}

interval_t interval_t::operator/(const uint64_t& rhs) const {
    interval_t result{};
    int32_t monthsRemainder = months % rhs;
    int32_t daysRemainder = (days + monthsRemainder * Interval::DAYS_PER_MONTH) % rhs;
    result.months = months / rhs;
    result.days = (days + monthsRemainder * Interval::DAYS_PER_MONTH) / rhs;
    result.micros = (micros + daysRemainder * Interval::MICROS_PER_DAY) / rhs;
    return result;
}

void Interval::addition(interval_t& result, uint64_t number, std::string specifierStr) {
    StringUtils::toLower(specifierStr);
    if (specifierStr == "year" || specifierStr == "years" || specifierStr == "y") {
        result.months += number * MONTHS_PER_YEAR;
    } else if (specifierStr == "month" || specifierStr == "months" || specifierStr == "mon") {
        result.months += number;
    } else if (specifierStr == "day" || specifierStr == "days" || specifierStr == "d") {
        result.days += number;
    } else if (specifierStr == "hour" || specifierStr == "hours" || specifierStr == "h") {
        result.micros += number * MICROS_PER_HOUR;
    } else if (specifierStr == "minute" || specifierStr == "minutes" || specifierStr == "m") {
        result.micros += number * MICROS_PER_MINUTE;
    } else if (specifierStr == "second" || specifierStr == "seconds" || specifierStr == "s") {
        result.micros += number * MICROS_PER_SEC;
    } else if (specifierStr == "millisecond" || specifierStr == "milliseconds" ||
               specifierStr == "ms" || specifierStr == "msec") {
        result.micros += number * MICROS_PER_MSEC;
    } else if (specifierStr == "microsecond" || specifierStr == "microseconds" ||
               specifierStr == "us") {
        result.micros += number;
    } else {
        throw ConversionException("Unrecognized interval specifier string: " + specifierStr + ".");
    }
}

template<class T>
T intervalTryCastInteger(int64_t input) {
    if (std::is_same<T, int8_t>()) {
        int8_t result = 0;
        function::CastToInt8::operation<int64_t>(input, result);
        return result;
    } else if (std::is_same<T, int16_t>()) {
        int16_t result = 0;
        function::CastToInt16::operation<int64_t>(input, result);
        return result;
    } else if (std::is_same<T, int32_t>()) {
        int32_t result = 0;
        function::CastToInt32::operation<int64_t>(input, result);
        return result;
    } else if (std::is_same<T, int64_t>()) {
        int64_t result = 0;
        function::CastToInt64::operation<int64_t>(input, result);
        return result;
    } else {
        throw ConversionException("The destination is not an integer");
    }
}

template<class T>
void intervalTryAddition(T& target, int64_t input, int64_t multiplier, int64_t fraction = 0) {
    int64_t addition = 0;
    try {
        function::Multiply::operation(input, multiplier, addition);
    } catch (const OverflowException& e) {
        throw OverflowException{"Interval value is out of range"};
    }
    T additionBase = intervalTryCastInteger<T>(addition);
    try {
        function::Add::operation(target, additionBase, target);
    } catch (const OverflowException& e) {
        throw OverflowException{"Interval value is out of range"};
    }
    if (fraction) {
        //	Add in (fraction * multiplier) / MICROS_PER_SEC
        //	This is always in range
        addition = (fraction * multiplier) / Interval::MICROS_PER_SEC;
        additionBase = intervalTryCastInteger<T>(addition);
        try {
            function::Add::operation(target, additionBase, target);
        } catch (const OverflowException& e) {
            throw OverflowException{"Interval fraction is out of range"};
        }
    }
}

interval_t Interval::fromCString(const char* str, uint64_t len) {
    interval_t result;
    uint64_t pos = 0;
    uint64_t startPos = 0;
    bool foundAny = false;
    int64_t number = 0;
    int64_t fraction = 0;
    DatePartSpecifier specifier{};
    std::string specifierStr{};

    result.days = 0;
    result.micros = 0;
    result.months = 0;

    if (len == 0) {
        throw ConversionException("Error occurred during parsing interval. Given empty string.");
    }

    if (str[pos] == '@') {
        pos++;
    }

parse_interval:
    for (; pos < len; pos++) {
        char c = str[pos];
        if (isspace(c)) {
            // skip spaces
            continue;
        } else if (isdigit(c)) {
            // start parsing a number
            goto interval_parse_number;
        } else {
            // unrecognized character, expected a number or end of string
            throw ConversionException("Error occurred during parsing interval. Given: \"" +
                                      std::string(str, len) + "\".");
        }
    }
    goto end_of_string;

interval_parse_number:
    startPos = pos;
    for (; pos < len; pos++) {
        char c = str[pos];
        if (isdigit(c)) {
            // the number continues
            continue;
        } else if (c == ':') {
            // colon: we are parsing a time
            goto interval_parse_time;
        } else {
            // finished the number, parse it from the string
            function::CastString::operation(ku_string_t{str + startPos, pos - startPos}, number);
            fraction = 0;
            if (c == '.') {
                // we expect some microseconds
                int32_t mult = 100000;
                for (++pos; pos < len && isdigit(str[pos]); ++pos, mult /= 10) {
                    if (mult > 0) {
                        fraction += int64_t(str[pos] - '0') * mult;
                    }
                }
            }
            goto interval_parse_identifier;
        }
    }
    goto interval_parse_identifier;

interval_parse_time: {
    // parse the remainder of the time as a Time type
    dtime_t time;
    uint64_t tmpPos = 0;
    if (!Time::tryConvertInterval(str + startPos, len - startPos, tmpPos, time)) {
        throw ConversionException("Error occurred during parsing time. Given: \"" +
                                  std::string(str + startPos, len - startPos) + "\".");
    }
    result.micros += time.micros;
    foundAny = true;
    goto end_of_string;
}

interval_parse_identifier:
    for (; pos < len; pos++) {
        char c = str[pos];
        if (isspace(c)) {
            // skip spaces at the start
            continue;
        } else {
            break;
        }
    }
    // now parse the identifier
    startPos = pos;
    for (; pos < len; pos++) {
        char c = str[pos];
        if (!isspace(c)) {
            // keep parsing the string
            continue;
        } else {
            break;
        }
    }
    specifierStr = std::string(str + startPos, pos - startPos);

    // Specifier string is empty, missing field name
    if (specifierStr.empty()) {
        throw ConversionException("Error occurred during parsing interval. Field name is missing.");
    }

    tryGetDatePartSpecifier(specifierStr, specifier);

    switch (specifier) {
    case DatePartSpecifier::MILLENNIUM:
        intervalTryAddition<int32_t>(result.months, number, MONTHS_PER_MILLENIUM, fraction);
        break;
    case DatePartSpecifier::CENTURY:
        intervalTryAddition<int32_t>(result.months, number, MONTHS_PER_CENTURY, fraction);
        break;
    case DatePartSpecifier::DECADE:
        intervalTryAddition<int32_t>(result.months, number, MONTHS_PER_DECADE, fraction);
        break;
    case DatePartSpecifier::YEAR:
        intervalTryAddition<int32_t>(result.months, number, MONTHS_PER_YEAR, fraction);
        break;
    case DatePartSpecifier::QUARTER:
        intervalTryAddition<int32_t>(result.months, number, MONTHS_PER_QUARTER, fraction);
        // Reduce to fraction of a month
        fraction *= MONTHS_PER_QUARTER;
        fraction %= MICROS_PER_SEC;
        intervalTryAddition<int32_t>(result.days, 0, DAYS_PER_MONTH, fraction);
        break;
    case DatePartSpecifier::MONTH:
        intervalTryAddition<int32_t>(result.months, number, 1);
        intervalTryAddition<int32_t>(result.days, 0, DAYS_PER_MONTH, fraction);
        break;
    case DatePartSpecifier::DAY:
        intervalTryAddition<int32_t>(result.days, number, 1);
        intervalTryAddition<int64_t>(result.micros, 0, MICROS_PER_DAY, fraction);
        break;
    case DatePartSpecifier::WEEK:
        intervalTryAddition<int32_t>(result.days, number, DAYS_PER_WEEK, fraction);
        // Reduce to fraction of a day
        fraction *= DAYS_PER_WEEK;
        fraction %= MICROS_PER_SEC;
        intervalTryAddition<int64_t>(result.micros, 0, MICROS_PER_DAY, fraction);
        break;
    case DatePartSpecifier::HOUR:
        intervalTryAddition<int64_t>(result.micros, number, MICROS_PER_HOUR, fraction);
        break;
    case DatePartSpecifier::MINUTE:
        intervalTryAddition<int64_t>(result.micros, number, MICROS_PER_MINUTE, fraction);
        break;
    case DatePartSpecifier::SECOND:
        intervalTryAddition<int64_t>(result.micros, number, MICROS_PER_SEC, fraction);
        break;
    case DatePartSpecifier::MILLISECOND:
        intervalTryAddition<int64_t>(result.micros, number, MICROS_PER_MSEC, fraction);
        break;
    case DatePartSpecifier::MICROSECOND:
        // Round the fraction
        number += (fraction * 2) / MICROS_PER_SEC;
        intervalTryAddition<int64_t>(result.micros, number, 1);
        break;
    default:
        throw ConversionException("Unrecognized interval specifier string: " + specifierStr + ".");
    }

    foundAny = true;
    goto parse_interval;

end_of_string:
    if (!foundAny) {
        throw ConversionException(
            "Error occurred during parsing interval. Given: \"" + std::string(str, len) + "\".");
    }
    return result;
}

std::string Interval::toString(interval_t interval) {
    char buffer[70];
    uint64_t length = IntervalToStringCast::Format(interval, buffer);
    return std::string(buffer, length);
}

// helper function of interval comparison
void Interval::normalizeIntervalEntries(interval_t input, int64_t& months, int64_t& days,
    int64_t& micros) {
    int64_t extra_months_d = input.days / Interval::DAYS_PER_MONTH;
    int64_t extra_months_micros = input.micros / Interval::MICROS_PER_MONTH;
    input.days -= extra_months_d * Interval::DAYS_PER_MONTH;
    input.micros -= extra_months_micros * Interval::MICROS_PER_MONTH;

    int64_t extra_days_micros = input.micros / Interval::MICROS_PER_DAY;
    input.micros -= extra_days_micros * Interval::MICROS_PER_DAY;

    months = input.months + extra_months_d + extra_months_micros;
    days = input.days + extra_days_micros;
    micros = input.micros;
}

bool Interval::greaterThan(const interval_t& left, const interval_t& right) {
    int64_t lMonths = 0, lDays = 0, lMicros = 0;
    int64_t rMonths = 0, rDays = 0, rMicros = 0;
    normalizeIntervalEntries(left, lMonths, lDays, lMicros);
    normalizeIntervalEntries(right, rMonths, rDays, rMicros);
    if (lMonths > rMonths) {
        return true;
    } else if (lMonths < rMonths) {
        return false;
    }
    if (lDays > rDays) {
        return true;
    } else if (lDays < rDays) {
        return false;
    }
    return lMicros > rMicros;
}

void Interval::tryGetDatePartSpecifier(std::string specifier, DatePartSpecifier& result) {
    StringUtils::toLower(specifier);
    if (specifier == "year" || specifier == "yr" || specifier == "y" || specifier == "years" ||
        specifier == "yrs") {
        result = DatePartSpecifier::YEAR;
    } else if (specifier == "month" || specifier == "mon" || specifier == "months" ||
               specifier == "mons") {
        result = DatePartSpecifier::MONTH;
    } else if (specifier == "day" || specifier == "days" || specifier == "d" ||
               specifier == "dayofmonth") {
        result = DatePartSpecifier::DAY;
    } else if (specifier == "decade" || specifier == "dec" || specifier == "decades" ||
               specifier == "decs") {
        result = DatePartSpecifier::DECADE;
    } else if (specifier == "century" || specifier == "cent" || specifier == "centuries" ||
               specifier == "c") {
        result = DatePartSpecifier::CENTURY;
    } else if (specifier == "millennium" || specifier == "mil" || specifier == "millenniums" ||
               specifier == "millennia" || specifier == "mils" || specifier == "millenium" ||
               specifier == "milleniums") {
        result = DatePartSpecifier::MILLENNIUM;
    } else if (specifier == "microseconds" || specifier == "microsecond" || specifier == "us" ||
               specifier == "usec" || specifier == "usecs" || specifier == "usecond" ||
               specifier == "useconds") {
        result = DatePartSpecifier::MICROSECOND;
    } else if (specifier == "milliseconds" || specifier == "millisecond" || specifier == "ms" ||
               specifier == "msec" || specifier == "msecs" || specifier == "msecond" ||
               specifier == "mseconds") {
        result = DatePartSpecifier::MILLISECOND;
    } else if (specifier == "second" || specifier == "sec" || specifier == "seconds" ||
               specifier == "secs" || specifier == "s") {
        result = DatePartSpecifier::SECOND;
    } else if (specifier == "minute" || specifier == "min" || specifier == "minutes" ||
               specifier == "mins" || specifier == "m") {
        result = DatePartSpecifier::MINUTE;
    } else if (specifier == "hour" || specifier == "hr" || specifier == "hours" ||
               specifier == "hrs" || specifier == "h") {
        result = DatePartSpecifier::HOUR;
    } else if (specifier == "week" || specifier == "weeks" || specifier == "w" ||
               specifier == "weekofyear") {
        // ISO week number
        result = DatePartSpecifier::WEEK;
    } else if (specifier == "quarter" || specifier == "quarters") {
        // quarter of the year (1-4)
        result = DatePartSpecifier::QUARTER;
    } else {
        throw ConversionException("Unrecognized interval specifier string: " + specifier + ".");
    }
}

int32_t Interval::getIntervalPart(DatePartSpecifier specifier, interval_t interval) {
    switch (specifier) {
    case DatePartSpecifier::YEAR:
        return interval.months / Interval::MONTHS_PER_YEAR;
    case DatePartSpecifier::MONTH:
        return interval.months % Interval::MONTHS_PER_YEAR;
    case DatePartSpecifier::DAY:
        return interval.days;
    case DatePartSpecifier::DECADE:
        return interval.months / Interval::MONTHS_PER_DECADE;
    case DatePartSpecifier::CENTURY:
        return interval.months / Interval::MONTHS_PER_CENTURY;
    case DatePartSpecifier::MILLENNIUM:
        return interval.months / Interval::MONTHS_PER_MILLENIUM;
    case DatePartSpecifier::QUARTER:
        return getIntervalPart(DatePartSpecifier::MONTH, interval) / Interval::MONTHS_PER_QUARTER +
               1;
    case DatePartSpecifier::MICROSECOND:
        return interval.micros % Interval::MICROS_PER_MINUTE;
    case DatePartSpecifier::MILLISECOND:
        return getIntervalPart(DatePartSpecifier::MICROSECOND, interval) /
               Interval::MICROS_PER_MSEC;
    case DatePartSpecifier::SECOND:
        return getIntervalPart(DatePartSpecifier::MICROSECOND, interval) / Interval::MICROS_PER_SEC;
    case DatePartSpecifier::MINUTE:
        return (interval.micros % Interval::MICROS_PER_HOUR) / Interval::MICROS_PER_MINUTE;
    case DatePartSpecifier::HOUR:
        return interval.micros / Interval::MICROS_PER_HOUR;
    default:
        KU_UNREACHABLE;
    }
}

int64_t Interval::getMicro(const interval_t& val) {
    return val.micros + val.months * MICROS_PER_MONTH + val.days * MICROS_PER_DAY;
}

int64_t Interval::getNanoseconds(const interval_t& val) {
    return getMicro(val) * NANOS_PER_MICRO;
}

const regex::RE2& Interval::regexPattern1() {
    static regex::RE2 retval(
        "(?i)((0|[1-9]\\d*) "
        "+(YEARS?|YRS?|Y|MONS?|MONTHS?|DAYS?|D|DAYOFMONTH|DECADES?|DECS?|CENTURY|CENTURIES|CENT|C|"
        "MILLENN?IUMS?|MILS?|MILLENNIA|MICROSECONDS?|US|USECS?|USECONDS?|MILLISECONDS?|MS|SECONDS?|"
        "SECS?|S|MINUTES?|MINS?|M|HOURS?|HRS?|H|WEEKS?|WEEKOFYEAR|W|QUARTERS?))( +(0|[1-9]\\d*) "
        "+(YEARS?|YRS?|Y|MONS?|MONTHS?|DAYS?|D|DAYOFMONTH|DECADES?|DECS?|CENTURY|CENTURIES|CENT|C|"
        "MILLENN?IUMS?|MILS?|MILLENNIA|MICROSECONDS?|US|USECS?|USECONDS?|MILLISECONDS?|MS|SECONDS?|"
        "SECS?|S|MINUTES?|MINS?|M|HOURS?|HRS?|H|WEEKS?|WEEKOFYEAR|W|QUARTERS?))*( "
        "+\\d+:\\d{2}:\\d{2}(\\.\\d+)?)?");
    return retval;
}

const regex::RE2& Interval::regexPattern2() {
    static regex::RE2 retval("\\d+:\\d{2}:\\d{2}(\\.\\d+)?");
    return retval;
}

} // namespace common
} // namespace lbug
