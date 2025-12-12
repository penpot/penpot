#include "processor/operator/persistent/reader/parquet/parquet_timestamp.h"

#include <cstring>

namespace lbug {
namespace processor {

common::timestamp_t ParquetTimeStampUtils::impalaTimestampToTimestamp(const Int96& rawTS) {
    auto impalaUS = impalaTimestampToMicroseconds(rawTS);
    return common::Timestamp::fromEpochMicroSeconds(impalaUS);
}

common::timestamp_t ParquetTimeStampUtils::parquetTimestampMicrosToTimestamp(const int64_t& rawTS) {
    return common::Timestamp::fromEpochMicroSeconds(rawTS);
}

common::timestamp_t ParquetTimeStampUtils::parquetTimestampMsToTimestamp(const int64_t& rawTS) {
    return common::Timestamp::fromEpochMilliSeconds(rawTS);
}

common::timestamp_t ParquetTimeStampUtils::parquetTimestampNsToTimestamp(const int64_t& rawTS) {
    return common::Timestamp::fromEpochNanoSeconds(rawTS);
}

int64_t ParquetTimeStampUtils::impalaTimestampToMicroseconds(const Int96& impalaTimestamp) {
    int64_t daysSinceEpoch = impalaTimestamp.value[2] - JULIAN_TO_UNIX_EPOCH_DAYS;
    int64_t nanoSeconds = 0;
    memcpy(&nanoSeconds, &impalaTimestamp.value, sizeof(nanoSeconds));
    auto microseconds = nanoSeconds / NANOSECONDS_PER_MICRO;
    return daysSinceEpoch * MICROSECONDS_PER_DAY + microseconds;
}

common::date_t ParquetTimeStampUtils::parquetIntToDate(const int32_t& raw_date) {
    return common::date_t(raw_date);
}

} // namespace processor
} // namespace lbug
