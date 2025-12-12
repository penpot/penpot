#pragma once

#include <cstring>

#include "common/types/date_t.h"
#include "interval_t.h"

namespace lbug {
namespace common {

// This is copied from third_party/fmt/include/fmt/format.h and format-inl.h.
static const char digits[] = "0001020304050607080910111213141516171819"
                             "2021222324252627282930313233343536373839"
                             "4041424344454647484950515253545556575859"
                             "6061626364656667686970717273747576777879"
                             "8081828384858687888990919293949596979899";

//! NumericHelper is a static class that holds helper functions for integers/doubles
class NumericHelper {
public:
    // Formats value in reverse and returns a pointer to the beginning.
    template<class T>
    static char* FormatUnsigned(T value, char* ptr) {
        while (value >= 100) {
            // Integer division is slow so do it for a group of two digits instead
            // of for every digit. The idea comes from the talk by Alexandrescu
            // "Three Optimization Tips for C++".
            auto index = static_cast<unsigned>((value % 100) * 2);
            value /= 100;
            *--ptr = digits[index + 1];
            *--ptr = digits[index];
        }
        if (value < 10) {
            *--ptr = static_cast<char>('0' + value);
            return ptr;
        }
        auto index = static_cast<unsigned>(value * 2);
        *--ptr = digits[index + 1];
        *--ptr = digits[index];
        return ptr;
    }
    static int getUnsignedInt64Length(uint64_t value) {
        if (value >= 10000000000ULL) {
            if (value >= 1000000000000000ULL) {
                int length = 16;
                length += value >= 10000000000000000ULL;
                length += value >= 100000000000000000ULL;
                length += value >= 1000000000000000000ULL;
                length += value >= 10000000000000000000ULL;
                return length;
            } else {
                int length = 11;
                length += value >= 100000000000ULL;
                length += value >= 1000000000000ULL;
                length += value >= 10000000000000ULL;
                length += value >= 100000000000000ULL;
                return length;
            }
        } else {
            if (value >= 100000ULL) {
                int length = 6;
                length += value >= 1000000ULL;
                length += value >= 10000000ULL;
                length += value >= 100000000ULL;
                length += value >= 1000000000ULL;
                return length;
            } else {
                int length = 1;
                length += value >= 10ULL;
                length += value >= 100ULL;
                length += value >= 1000ULL;
                length += value >= 10000ULL;
                return length;
            }
        }
    }
};

struct DateToStringCast {
    static uint64_t Length(int32_t date[], uint64_t& yearLength, bool& addBC) {
        // format is YYYY-MM-DD with optional (BC) at the end
        // regular length is 10
        uint64_t length = 6;
        yearLength = 4;
        addBC = false;
        if (date[0] <= 0) {
            // add (BC) suffix
            length += strlen(Date::BC_SUFFIX);
            date[0] = -date[0] + 1;
            addBC = true;
        }

        // potentially add extra characters depending on length of year
        yearLength += date[0] >= 10000;
        yearLength += date[0] >= 100000;
        yearLength += date[0] >= 1000000;
        yearLength += date[0] >= 10000000;
        length += yearLength;
        return length;
    }

    static void Format(char* data, int32_t date[], uint64_t yearLen, bool addBC) {
        // now we write the string, first write the year
        auto endptr = data + yearLen;
        endptr = NumericHelper::FormatUnsigned(date[0], endptr);
        // add optional leading zeros
        while (endptr > data) {
            *--endptr = '0';
        }
        // now write the month and day
        auto ptr = data + yearLen;
        for (int i = 1; i <= 2; i++) {
            ptr[0] = '-';
            if (date[i] < 10) {
                ptr[1] = '0';
                ptr[2] = '0' + date[i];
            } else {
                auto index = static_cast<unsigned>(date[i] * 2);
                ptr[1] = digits[index];
                ptr[2] = digits[index + 1];
            }
            ptr += 3;
        }
        // optionally add BC to the end of the date
        if (addBC) {
            memcpy(ptr, Date::BC_SUFFIX, // NOLINT(bugprone-not-null-terminated-result): no need to
                                         // put null terminator
                strlen(Date::BC_SUFFIX));
        }
    }
};

struct TimeToStringCast {
    // Format microseconds to a buffer of length 6. Returns the number of trailing zeros
    static int32_t FormatMicros(uint32_t microseconds, char micro_buffer[]) {
        char* endptr = micro_buffer + 6;
        endptr = NumericHelper::FormatUnsigned<uint32_t>(microseconds, endptr);
        while (endptr > micro_buffer) {
            *--endptr = '0';
        }
        uint64_t trailing_zeros = 0;
        for (uint64_t i = 5; i > 0; i--) {
            if (micro_buffer[i] != '0') {
                break;
            }
            trailing_zeros++;
        }
        return trailing_zeros;
    }

    static uint64_t Length(int32_t time[], char micro_buffer[]) {
        // format is HH:MM:DD.MS
        // microseconds come after the time with a period separator
        uint64_t length = 0;
        if (time[3] == 0) {
            // no microseconds
            // format is HH:MM:DD
            length = 8;
        } else {
            length = 15;
            // for microseconds, we truncate any trailing zeros (i.e. "90000" becomes ".9")
            // first write the microseconds to the microsecond buffer
            // we write backwards and pad with zeros to the left
            // now we figure out how many digits we need to include by looking backwards
            // and checking how many zeros we encounter
            length -= FormatMicros(time[3], micro_buffer);
        }
        return length;
    }

    static void FormatTwoDigits(char* ptr, int32_t value) {
        if (value < 10) {
            ptr[0] = '0';
            ptr[1] = '0' + value;
        } else {
            auto index = static_cast<unsigned>(value * 2);
            ptr[0] = digits[index];
            ptr[1] = digits[index + 1];
        }
    }

    static void Format(char* data, uint64_t length, int32_t time[], char micro_buffer[]) {
        // first write hour, month and day
        auto ptr = data;
        ptr[2] = ':';
        ptr[5] = ':';
        for (int i = 0; i <= 2; i++) {
            FormatTwoDigits(ptr, time[i]);
            ptr += 3;
        }
        if (length > 8) {
            // write the micro seconds at the end
            data[8] = '.';
            memcpy(data + 9, micro_buffer, length - 9);
        }
    }
};

struct IntervalToStringCast {
    static void FormatSignedNumber(int64_t value, char buffer[], uint64_t& length) {
        int sign = -(value < 0);
        uint64_t unsigned_value = (value ^ sign) - sign;
        length += NumericHelper::getUnsignedInt64Length(unsigned_value) - sign;
        auto endptr = buffer + length;
        endptr = NumericHelper::FormatUnsigned<uint64_t>(unsigned_value, endptr);
        if (sign) {
            *--endptr = '-';
        }
    }

    static void FormatTwoDigits(int64_t value, char buffer[], uint64_t& length) {
        TimeToStringCast::FormatTwoDigits(buffer + length, value);
        length += 2;
    }

    static void FormatIntervalValue(int32_t value, char buffer[], uint64_t& length,
        const char* name, uint64_t name_len) {
        if (value == 0) {
            return;
        }
        if (length != 0) {
            // space if there is already something in the buffer
            buffer[length++] = ' ';
        }
        FormatSignedNumber(value, buffer, length);
        // append the name together with a potential "s" (for plurals)
        memcpy(buffer + length, name, name_len);
        length += name_len;
        if (value != 1) {
            buffer[length++] = 's';
        }
    }

    //! Formats an interval to a buffer, the buffer should be >=70 characters
    //! years: 17 characters (max value: "-2147483647 years")
    //! months: 9 (max value: "12 months")
    //! days: 16 characters (max value: "-2147483647 days")
    //! time: 24 characters (max value: -2562047788:00:00.123456)
    //! spaces between all characters (+3 characters)
    //! Total: 70 characters
    //! Returns the length of the interval
    static uint64_t Format(interval_t interval, char buffer[]) {
        uint64_t length = 0;
        if (interval.months != 0) {
            int32_t years = interval.months / 12;
            int32_t months = interval.months - years * 12;
            // format the years and months
            FormatIntervalValue(years, buffer, length, " year", 5);
            FormatIntervalValue(months, buffer, length, " month", 6);
        }
        if (interval.days != 0) {
            // format the days
            FormatIntervalValue(interval.days, buffer, length, " day", 4);
        }
        if (interval.micros != 0) {
            if (length != 0) {
                // space if there is already something in the buffer
                buffer[length++] = ' ';
            }
            int64_t micros = interval.micros;
            if (micros < 0) {
                // negative time: append negative sign
                buffer[length++] = '-';
                micros = -micros;
            }
            int64_t hour = micros / Interval::MICROS_PER_HOUR;
            micros -= hour * Interval::MICROS_PER_HOUR;
            int64_t min = micros / Interval::MICROS_PER_MINUTE;
            micros -= min * Interval::MICROS_PER_MINUTE;
            int64_t sec = micros / Interval::MICROS_PER_SEC;
            micros -= sec * Interval::MICROS_PER_SEC;

            if (hour < 10) {
                buffer[length++] = '0';
            }
            FormatSignedNumber(hour, buffer, length);
            buffer[length++] = ':';
            FormatTwoDigits(min, buffer, length);
            buffer[length++] = ':';
            FormatTwoDigits(sec, buffer, length);
            if (micros != 0) {
                buffer[length++] = '.';
                auto trailing_zeros = TimeToStringCast::FormatMicros(micros, buffer + length);
                length += 6 - trailing_zeros;
            }
        } else if (length == 0) {
            // empty interval: default to 00:00:00
            strcpy(buffer, "00:00:00"); // NOLINT(clang-analyzer-security.insecureAPI.strcpy):
                                        // safety guaranteed by Length().
            return 8;
        }
        return length;
    }
};

} // namespace common
} // namespace lbug
