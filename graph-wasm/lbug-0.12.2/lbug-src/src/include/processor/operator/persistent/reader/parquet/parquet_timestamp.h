#pragma once

#include "common/types/timestamp_t.h"

namespace lbug {
namespace processor {

struct Int96 {
    uint32_t value[3];
};

struct ParquetTimeStampUtils {
    static constexpr int64_t JULIAN_TO_UNIX_EPOCH_DAYS = 2440588LL;
    static constexpr int64_t MILLISECONDS_PER_DAY = 86400000LL;
    static constexpr int64_t MICROSECONDS_PER_DAY = MILLISECONDS_PER_DAY * 1000LL;
    static constexpr int64_t NANOSECONDS_PER_MICRO = 1000LL;

    static common::timestamp_t impalaTimestampToTimestamp(const Int96& rawTS);
    static common::timestamp_t parquetTimestampMicrosToTimestamp(const int64_t& rawTS);
    static common::timestamp_t parquetTimestampMsToTimestamp(const int64_t& rawTS);
    static common::timestamp_t parquetTimestampNsToTimestamp(const int64_t& rawTS);
    static int64_t impalaTimestampToMicroseconds(const Int96& impalaTimestamp);
    static common::date_t parquetIntToDate(const int32_t& raw_date);
};

} // namespace processor
} // namespace lbug
