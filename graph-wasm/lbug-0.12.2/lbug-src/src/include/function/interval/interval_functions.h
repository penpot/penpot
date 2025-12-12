#pragma once

#include "common/types/interval_t.h"

namespace lbug {
namespace function {

struct ToYears {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.days = result.micros = 0;
        result.months = input * common::Interval::MONTHS_PER_YEAR;
    }
};

struct ToMonths {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.days = result.micros = 0;
        result.months = input;
    }
};

struct ToDays {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.micros = result.months = 0;
        result.days = input;
    }
};

struct ToHours {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.months = result.days = 0;
        result.micros = input * common::Interval::MICROS_PER_HOUR;
    }
};

struct ToMinutes {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.months = result.days = 0;
        result.micros = input * common::Interval::MICROS_PER_MINUTE;
    }
};

struct ToSeconds {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.months = result.days = 0;
        result.micros = input * common::Interval::MICROS_PER_SEC;
    }
};

struct ToMilliseconds {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.months = result.days = 0;
        result.micros = input * common::Interval::MICROS_PER_MSEC;
    }
};

struct ToMicroseconds {
    static inline void operation(int64_t& input, common::interval_t& result) {
        result.months = result.days = 0;
        result.micros = input;
    }
};

} // namespace function
} // namespace lbug
