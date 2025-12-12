#include "common/types/date_t.h"

#include "common/assert.h"
#include "common/exception/conversion.h"
#include "common/string_format.h"
#include "common/string_utils.h"
#include "common/types/cast_helpers.h"
#include "common/types/timestamp_t.h"
#include "re2.h"

namespace lbug {
namespace common {

date_t::date_t() : days{0} {}

date_t::date_t(int32_t days_p) : days(days_p) {}

bool date_t::operator==(const date_t& rhs) const {
    return days == rhs.days;
}

bool date_t::operator!=(const date_t& rhs) const {
    return days != rhs.days;
}

bool date_t::operator<=(const date_t& rhs) const {
    return days <= rhs.days;
}

bool date_t::operator<(const date_t& rhs) const {
    return days < rhs.days;
}

bool date_t::operator>(const date_t& rhs) const {
    return days > rhs.days;
}

bool date_t::operator>=(const date_t& rhs) const {
    return days >= rhs.days;
}

date_t date_t::operator+(const interval_t& interval) const {
    date_t result{};
    if (interval.months != 0) {
        int32_t year = 0, month = 0, day = 0, maxDayInMonth = 0;
        Date::convert(*this, year, month, day);
        int32_t year_diff = interval.months / Interval::MONTHS_PER_YEAR;
        year += year_diff;
        month += interval.months - year_diff * Interval::MONTHS_PER_YEAR;
        if (month > Interval::MONTHS_PER_YEAR) {
            year++;
            month -= Interval::MONTHS_PER_YEAR;
        } else if (month <= 0) {
            year--;
            month += Interval::MONTHS_PER_YEAR;
        }
        // handle date overflow
        // example: 2020-01-31 + "1 months"
        maxDayInMonth = Date::monthDays(year, month);
        day = day > maxDayInMonth ? maxDayInMonth : day;
        result = Date::fromDate(year, month, day);
    } else {
        result = *this;
    }
    if (interval.days != 0) {
        result.days += interval.days;
    }
    if (interval.micros != 0) {
        result.days += int32_t(interval.micros / Interval::MICROS_PER_DAY);
    }
    return result;
}

date_t date_t::operator-(const interval_t& interval) const {
    interval_t inverseRight{};
    inverseRight.months = -interval.months;
    inverseRight.days = -interval.days;
    inverseRight.micros = -interval.micros;
    return *this + inverseRight;
}

int64_t date_t::operator-(const date_t& rhs) const {
    return (*this).days - rhs.days;
}

bool date_t::operator==(const timestamp_t& rhs) const {
    return Timestamp::fromDateTime(*this, dtime_t(0)).value == rhs.value;
}

bool date_t::operator!=(const timestamp_t& rhs) const {
    return !(*this == rhs);
}

bool date_t::operator<(const timestamp_t& rhs) const {
    return Timestamp::fromDateTime(*this, dtime_t(0)).value < rhs.value;
}

bool date_t::operator<=(const timestamp_t& rhs) const {
    return (*this) < rhs || (*this) == rhs;
}

bool date_t::operator>(const timestamp_t& rhs) const {
    return !(*this <= rhs);
}

bool date_t::operator>=(const timestamp_t& rhs) const {
    return !(*this < rhs);
}

date_t date_t::operator+(const int32_t& day) const {
    return date_t(this->days + day);
};
date_t date_t::operator-(const int32_t& day) const {
    return date_t(this->days - day);
};

const int32_t Date::NORMAL_DAYS[] = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
const int32_t Date::LEAP_DAYS[] = {0, 31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
const int32_t Date::CUMULATIVE_LEAP_DAYS[] = {0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335,
    366};
const int32_t Date::CUMULATIVE_DAYS[] = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334,
    365};
const int8_t Date::MONTH_PER_DAY_OF_YEAR[] = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
    5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
    6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
    8, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 10,
    10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
    10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11,
    11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12};
const int8_t Date::LEAP_MONTH_PER_DAY_OF_YEAR[] = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
    5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
    6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
    8, 8, 8, 8, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
    9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
    10, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11,
    11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12};
const int32_t Date::CUMULATIVE_YEAR_DAYS[] = {0, 365, 730, 1096, 1461, 1826, 2191, 2557, 2922, 3287,
    3652, 4018, 4383, 4748, 5113, 5479, 5844, 6209, 6574, 6940, 7305, 7670, 8035, 8401, 8766, 9131,
    9496, 9862, 10227, 10592, 10957, 11323, 11688, 12053, 12418, 12784, 13149, 13514, 13879, 14245,
    14610, 14975, 15340, 15706, 16071, 16436, 16801, 17167, 17532, 17897, 18262, 18628, 18993,
    19358, 19723, 20089, 20454, 20819, 21184, 21550, 21915, 22280, 22645, 23011, 23376, 23741,
    24106, 24472, 24837, 25202, 25567, 25933, 26298, 26663, 27028, 27394, 27759, 28124, 28489,
    28855, 29220, 29585, 29950, 30316, 30681, 31046, 31411, 31777, 32142, 32507, 32872, 33238,
    33603, 33968, 34333, 34699, 35064, 35429, 35794, 36160, 36525, 36890, 37255, 37621, 37986,
    38351, 38716, 39082, 39447, 39812, 40177, 40543, 40908, 41273, 41638, 42004, 42369, 42734,
    43099, 43465, 43830, 44195, 44560, 44926, 45291, 45656, 46021, 46387, 46752, 47117, 47482,
    47847, 48212, 48577, 48942, 49308, 49673, 50038, 50403, 50769, 51134, 51499, 51864, 52230,
    52595, 52960, 53325, 53691, 54056, 54421, 54786, 55152, 55517, 55882, 56247, 56613, 56978,
    57343, 57708, 58074, 58439, 58804, 59169, 59535, 59900, 60265, 60630, 60996, 61361, 61726,
    62091, 62457, 62822, 63187, 63552, 63918, 64283, 64648, 65013, 65379, 65744, 66109, 66474,
    66840, 67205, 67570, 67935, 68301, 68666, 69031, 69396, 69762, 70127, 70492, 70857, 71223,
    71588, 71953, 72318, 72684, 73049, 73414, 73779, 74145, 74510, 74875, 75240, 75606, 75971,
    76336, 76701, 77067, 77432, 77797, 78162, 78528, 78893, 79258, 79623, 79989, 80354, 80719,
    81084, 81450, 81815, 82180, 82545, 82911, 83276, 83641, 84006, 84371, 84736, 85101, 85466,
    85832, 86197, 86562, 86927, 87293, 87658, 88023, 88388, 88754, 89119, 89484, 89849, 90215,
    90580, 90945, 91310, 91676, 92041, 92406, 92771, 93137, 93502, 93867, 94232, 94598, 94963,
    95328, 95693, 96059, 96424, 96789, 97154, 97520, 97885, 98250, 98615, 98981, 99346, 99711,
    100076, 100442, 100807, 101172, 101537, 101903, 102268, 102633, 102998, 103364, 103729, 104094,
    104459, 104825, 105190, 105555, 105920, 106286, 106651, 107016, 107381, 107747, 108112, 108477,
    108842, 109208, 109573, 109938, 110303, 110669, 111034, 111399, 111764, 112130, 112495, 112860,
    113225, 113591, 113956, 114321, 114686, 115052, 115417, 115782, 116147, 116513, 116878, 117243,
    117608, 117974, 118339, 118704, 119069, 119435, 119800, 120165, 120530, 120895, 121260, 121625,
    121990, 122356, 122721, 123086, 123451, 123817, 124182, 124547, 124912, 125278, 125643, 126008,
    126373, 126739, 127104, 127469, 127834, 128200, 128565, 128930, 129295, 129661, 130026, 130391,
    130756, 131122, 131487, 131852, 132217, 132583, 132948, 133313, 133678, 134044, 134409, 134774,
    135139, 135505, 135870, 136235, 136600, 136966, 137331, 137696, 138061, 138427, 138792, 139157,
    139522, 139888, 140253, 140618, 140983, 141349, 141714, 142079, 142444, 142810, 143175, 143540,
    143905, 144271, 144636, 145001, 145366, 145732, 146097};

void Date::extractYearOffset(int32_t& n, int32_t& year, int32_t& year_offset) {
    year = Date::EPOCH_YEAR;
    // first we normalize n to be in the year range [1970, 2370]
    // since leap years repeat every 400 years, we can safely normalize just by "shifting" the
    // CumulativeYearDays array
    while (n < 0) {
        n += Date::DAYS_PER_YEAR_INTERVAL;
        year -= Date::YEAR_INTERVAL;
    }
    while (n >= Date::DAYS_PER_YEAR_INTERVAL) {
        n -= Date::DAYS_PER_YEAR_INTERVAL;
        year += Date::YEAR_INTERVAL;
    }
    // interpolation search
    // we can find an upper bound of the year by assuming each year has 365 days
    year_offset = n / 365;
    // because of leap years we might be off by a little bit: compensate by decrementing the year
    // offset until we find our year
    while (n < Date::CUMULATIVE_YEAR_DAYS[year_offset]) {
        year_offset--;
        KU_ASSERT(year_offset >= 0);
    }
    year += year_offset;
    KU_ASSERT(n >= Date::CUMULATIVE_YEAR_DAYS[year_offset]);
}

void Date::convert(date_t date, int32_t& out_year, int32_t& out_month, int32_t& out_day) {
    auto n = date.days;
    int32_t year_offset = 0;
    Date::extractYearOffset(n, out_year, year_offset);

    out_day = n - Date::CUMULATIVE_YEAR_DAYS[year_offset];
    KU_ASSERT(out_day >= 0 && out_day <= 365);

    bool is_leap_year = (Date::CUMULATIVE_YEAR_DAYS[year_offset + 1] -
                            Date::CUMULATIVE_YEAR_DAYS[year_offset]) == 366;
    if (is_leap_year) {
        out_month = Date::LEAP_MONTH_PER_DAY_OF_YEAR[out_day];
        out_day -= Date::CUMULATIVE_LEAP_DAYS[out_month - 1];
    } else {
        out_month = Date::MONTH_PER_DAY_OF_YEAR[out_day];
        out_day -= Date::CUMULATIVE_DAYS[out_month - 1];
    }
    out_day++;
    KU_ASSERT(out_day > 0 && out_day <= (is_leap_year ? Date::LEAP_DAYS[out_month] :
                                                        Date::NORMAL_DAYS[out_month]));
    KU_ASSERT(out_month > 0 && out_month <= 12);
    KU_ASSERT(Date::isValid(out_year, out_month, out_day));
}

date_t Date::fromDate(int32_t year, int32_t month, int32_t day) {
    int32_t n = 0;
    if (!Date::isValid(year, month, day)) {
        throw ConversionException(stringFormat("Date out of range: {}-{}-{}.", year, month, day));
    }
    while (year < 1970) {
        year += Date::YEAR_INTERVAL;
        n -= Date::DAYS_PER_YEAR_INTERVAL;
    }
    while (year >= 2370) {
        year -= Date::YEAR_INTERVAL;
        n += Date::DAYS_PER_YEAR_INTERVAL;
    }
    n += Date::CUMULATIVE_YEAR_DAYS[year - 1970];
    n += Date::isLeapYear(year) ? Date::CUMULATIVE_LEAP_DAYS[month - 1] :
                                  Date::CUMULATIVE_DAYS[month - 1];
    n += day - 1;
    return date_t(n);
}

bool Date::parseDoubleDigit(const char* buf, uint64_t len, uint64_t& pos, int32_t& result) {
    if (pos < len && StringUtils::CharacterIsDigit(buf[pos])) {
        result = buf[pos++] - '0';
        if (pos < len && StringUtils::CharacterIsDigit(buf[pos])) {
            result = (buf[pos++] - '0') + result * 10;
        }
        return true;
    }
    return false;
}

// Checks if the date std::string given in buf complies with the YYYY:MM:DD format. Ignores leading
// and trailing spaces. Removes from the original DuckDB code the following features:
// 1) we don't parse "negative years", i.e., date formats that start with -.
// 2) we don't parse dates that end with trailing "BC".
bool Date::tryConvertDate(const char* buf, uint64_t len, uint64_t& pos, date_t& result,
    bool allowTrailing) {
    if (len == 0) {
        return false;
    }

    int32_t day = 0;
    int32_t month = -1;
    int32_t year = 0;

    // skip leading spaces
    while (pos < len && StringUtils::isSpace(buf[pos])) {
        pos++;
    }

    if (pos >= len) {
        return false;
    }

    if (!StringUtils::CharacterIsDigit(buf[pos])) {
        return false;
    }
    // first parse the year
    for (; pos < len && StringUtils::CharacterIsDigit(buf[pos]); pos++) {
        year = (buf[pos] - '0') + year * 10;
        if (year > Date::MAX_YEAR) {
            break;
        }
    }

    if (pos >= len) {
        return false;
    }

    // fetch the separator
    char sep = buf[pos++];
    if (sep != ' ' && sep != '-' && sep != '/' && sep != '\\') {
        // invalid separator
        return false;
    }

    // parse the month
    if (!Date::parseDoubleDigit(buf, len, pos, month)) {
        return false;
    }

    // Also checks that the separator is not the end of the string
    if (pos + 1 >= len) {
        return false;
    }

    if (buf[pos++] != sep) {
        return false;
    }

    // now parse the day
    if (!Date::parseDoubleDigit(buf, len, pos, day)) {
        return false;
    }

    // skip trailing spaces
    while (pos < len && StringUtils::isSpace((unsigned char)buf[pos])) {
        pos++;
    }
    // check position. if end was not reached, non-space chars remaining
    if (pos < len && !allowTrailing) {
        return false;
    }

    try {
        result = Date::fromDate(year, month, day);
    } catch (ConversionException& exc) {
        return false;
    }
    return true;
}

date_t Date::fromCString(const char* str, uint64_t len) {
    date_t result;
    uint64_t pos = 0;
    if (!tryConvertDate(str, len, pos, result)) {
        throw ConversionException("Error occurred during parsing date. Given: \"" +
                                  std::string(str, len) + "\". Expected format: (YYYY-MM-DD)");
    }
    return result;
}

std::string Date::toString(date_t date) {
    int32_t dateUnits[3];
    uint64_t yearLength = 0;
    bool addBC = false;
    Date::convert(date, dateUnits[0], dateUnits[1], dateUnits[2]);

    auto length = DateToStringCast::Length(dateUnits, yearLength, addBC);
    auto buffer = std::make_unique<char[]>(length);
    DateToStringCast::Format(buffer.get(), dateUnits, yearLength, addBC);
    return std::string(buffer.get(), length);
}

bool Date::isLeapYear(int32_t year) {
    return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

bool Date::isValid(int32_t year, int32_t month, int32_t day) {
    if (month < 1 || month > 12) {
        return false;
    }
    if (year < Date::MIN_YEAR || year > Date::MAX_YEAR) {
        return false;
    }
    if (day < 1) {
        return false;
    }
    return Date::isLeapYear(year) ? day <= Date::LEAP_DAYS[month] : day <= Date::NORMAL_DAYS[month];
}

int32_t Date::monthDays(int32_t year, int32_t month) {
    KU_ASSERT(month >= 1 && month <= 12);
    return Date::isLeapYear(year) ? Date::LEAP_DAYS[month] : Date::NORMAL_DAYS[month];
}

std::string Date::getDayName(date_t date) {
    std::string dayNames[] = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
        "Saturday"};
    return dayNames[(date.days < 0 ? 7 - ((-date.days + 3) % 7) : ((date.days + 3) % 7) + 1) % 7];
}

std::string Date::getMonthName(date_t date) {
    std::string monthNames[] = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};
    int32_t year = 0, month = 0, day = 0;
    Date::convert(date, year, month, day);
    return monthNames[month - 1];
}

date_t Date::getLastDay(date_t date) {
    int32_t year = 0, month = 0, day = 0;
    Date::convert(date, year, month, day);
    year += (month / 12);
    month %= 12;
    ++month;
    return Date::fromDate(year, month, 1) - 1;
}

int32_t Date::getDatePart(DatePartSpecifier specifier, date_t date) {
    int32_t year = 0, month = 0, day = 0;
    Date::convert(date, year, month, day);
    switch (specifier) {
    case DatePartSpecifier::YEAR: {
        int32_t yearOffset = 0;
        extractYearOffset(date.days, year, yearOffset);
        return year;
    }
    case DatePartSpecifier::MONTH:
        return month;
    case DatePartSpecifier::DAY:
        return day;
    case DatePartSpecifier::DECADE:
        return year / 10;
    case DatePartSpecifier::CENTURY:
        // From the PG docs:
        // "The first century starts at 0001-01-01 00:00:00 AD, although they did not know it at the
        // time. This definition applies to all Gregorian calendar countries. There is no century
        // number 0, you go from -1 century to 1 century. If you disagree with this, please write
        // your complaint to: Pope, Cathedral Saint-Peter of Roma, Vatican." (To be fair, His
        // Holiness had nothing to do with this - it was the lack of zero in the counting systems of
        // the time...).
        return year > 0 ? ((year - 1) / 100) + 1 : (year / 100) - 1;
    case DatePartSpecifier::MILLENNIUM:
        return year > 0 ? ((year - 1) / 1000) + 1 : (year / 1000) - 1;
    case DatePartSpecifier::QUARTER:
        return (month - 1) / Interval::MONTHS_PER_QUARTER + 1;
    default:
        return 0;
    }
}

date_t Date::trunc(DatePartSpecifier specifier, date_t date) {
    switch (specifier) {
    case DatePartSpecifier::YEAR:
        return Date::fromDate(Date::getDatePart(DatePartSpecifier::YEAR, date), 1 /* month */,
            1 /* day */);
    case DatePartSpecifier::MONTH:
        return Date::fromDate(Date::getDatePart(DatePartSpecifier::YEAR, date),
            Date::getDatePart(DatePartSpecifier::MONTH, date), 1 /* day */);
    case DatePartSpecifier::DAY:
        return date;
    case DatePartSpecifier::DECADE:
        return Date::fromDate((Date::getDatePart(DatePartSpecifier::YEAR, date) / 10) * 10,
            1 /* month */, 1 /* day */);
    case DatePartSpecifier::CENTURY:
        return Date::fromDate((Date::getDatePart(DatePartSpecifier::YEAR, date) / 100) * 100,
            1 /* month */, 1 /* day */);
    case DatePartSpecifier::MILLENNIUM:
        return Date::fromDate((Date::getDatePart(DatePartSpecifier::YEAR, date) / 1000) * 1000,
            1 /* month */, 1 /* day */);
    case DatePartSpecifier::QUARTER: {
        int32_t year = 0, month = 0, day = 0;
        Date::convert(date, year, month, day);
        month = 1 + (((month - 1) / 3) * 3);
        return Date::fromDate(year, month, 1);
    }
    default:
        return date;
    }
}

int64_t Date::getEpochNanoSeconds(const date_t& date) {
    return ((int64_t)date.days) * (Interval::MICROS_PER_DAY * Interval::NANOS_PER_MICRO);
}

const regex::RE2& Date::regexPattern() {
    static regex::RE2 retval("\\d{4}/\\d{1,2}/\\d{1,2}|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4} \\d{1,2} "
                             "\\d{1,2}|\\d{4}\\\\\\d{1,2}\\\\\\d{1,2}");
    return retval;
}

} // namespace common
} // namespace lbug
