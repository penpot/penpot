#pragma once

#include "cast_string_non_nested_functions.h"
#include "common/copier_config/csv_reader_config.h"
#include "common/type_utils.h"
#include "common/types/blob.h"
#include "common/types/uuid.h"
#include "common/vector/value_vector.h"

using namespace lbug::common;

namespace lbug {
namespace function {

struct LBUG_API CastString {
    static void copyStringToVector(ValueVector* vector, uint64_t vectorPos, std::string_view strVal,
        const CSVOption* option);

    template<typename T>
    static inline bool tryCast(const ku_string_t& input, T& result) {
        // try cast for signed integer types (not including int128)
        return trySimpleIntegerCast<T, true>(reinterpret_cast<const char*>(input.getData()),
            input.len, result);
    }

    template<typename T>
    static inline void operation(const ku_string_t& input, T& result,
        ValueVector* /*resultVector*/ = nullptr, uint64_t /*rowToAdd*/ = 0,
        const CSVOption* /*option*/ = nullptr) {
        // base case: int64
        simpleIntegerCast<T, true>(reinterpret_cast<const char*>(input.getData()), input.len,
            result, LogicalTypeID::INT64);
    }
};

template<>
inline void CastString::operation(const ku_string_t& input, int128_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<int128_t>(reinterpret_cast<const char*>(input.getData()), input.len, result,
        LogicalTypeID::INT128);
}

template<>
inline void CastString::operation(const ku_string_t& input, uint128_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<uint128_t, false>(reinterpret_cast<const char*>(input.getData()), input.len,
        result, LogicalTypeID::UINT128);
}

template<>
inline void CastString::operation(const ku_string_t& input, int32_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<int32_t>(reinterpret_cast<const char*>(input.getData()), input.len, result,
        LogicalTypeID::INT32);
}

template<>
inline void CastString::operation(const ku_string_t& input, int16_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<int16_t>(reinterpret_cast<const char*>(input.getData()), input.len, result,
        LogicalTypeID::INT16);
}

template<>
inline void CastString::operation(const ku_string_t& input, int8_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<int8_t>(reinterpret_cast<const char*>(input.getData()), input.len, result,
        LogicalTypeID::INT8);
}

template<>
inline void CastString::operation(const ku_string_t& input, uint64_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<uint64_t, false>(reinterpret_cast<const char*>(input.getData()), input.len,
        result, LogicalTypeID::UINT64);
}

template<>
inline void CastString::operation(const ku_string_t& input, uint32_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<uint32_t, false>(reinterpret_cast<const char*>(input.getData()), input.len,
        result, LogicalTypeID::UINT32);
}

template<>
inline void CastString::operation(const ku_string_t& input, uint16_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<uint16_t, false>(reinterpret_cast<const char*>(input.getData()), input.len,
        result, LogicalTypeID::UINT16);
}

template<>
inline void CastString::operation(const ku_string_t& input, uint8_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    simpleIntegerCast<uint8_t, false>(reinterpret_cast<const char*>(input.getData()), input.len,
        result, LogicalTypeID::UINT8);
}

template<>
inline void CastString::operation(const ku_string_t& input, float& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    doubleCast<float>(reinterpret_cast<const char*>(input.getData()), input.len, result,
        LogicalTypeID::FLOAT);
}

template<>
inline void CastString::operation(const ku_string_t& input, double& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    doubleCast<double>(reinterpret_cast<const char*>(input.getData()), input.len, result,
        LogicalTypeID::DOUBLE);
}

template<>
inline void CastString::operation(const ku_string_t& input, date_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    result = Date::fromCString((const char*)input.getData(), input.len);
}

template<>
inline void CastString::operation(const ku_string_t& input, timestamp_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    result = Timestamp::fromCString((const char*)input.getData(), input.len);
}

template<>
inline void CastString::operation(const ku_string_t& input, timestamp_ns_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    TryCastStringToTimestamp::cast<timestamp_ns_t>((const char*)input.getData(), input.len, result,
        LogicalTypeID::TIMESTAMP_NS);
}

template<>
inline void CastString::operation(const ku_string_t& input, timestamp_ms_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    TryCastStringToTimestamp::cast<timestamp_ms_t>((const char*)input.getData(), input.len, result,
        LogicalTypeID::TIMESTAMP_MS);
}

template<>
inline void CastString::operation(const ku_string_t& input, timestamp_sec_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    TryCastStringToTimestamp::cast<timestamp_sec_t>((const char*)input.getData(), input.len, result,
        LogicalTypeID::TIMESTAMP_SEC);
}

template<>
inline void CastString::operation(const ku_string_t& input, timestamp_tz_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    TryCastStringToTimestamp::cast<timestamp_tz_t>((const char*)input.getData(), input.len, result,
        LogicalTypeID::TIMESTAMP_TZ);
}

template<>
inline void CastString::operation(const ku_string_t& input, interval_t& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    result = Interval::fromCString((const char*)input.getData(), input.len);
}

template<>
inline void CastString::operation(const ku_string_t& input, bool& result,
    ValueVector* /*resultVector*/, uint64_t /*rowToAdd*/, const CSVOption* /*option*/) {
    castStringToBool(reinterpret_cast<const char*>(input.getData()), input.len, result);
}

template<>
void CastString::operation(const ku_string_t& input, blob_t& result, ValueVector* resultVector,
    uint64_t rowToAdd, const CSVOption* option);

template<>
void CastString::operation(const ku_string_t& input, ku_uuid_t& result, ValueVector* result_vector,
    uint64_t rowToAdd, const CSVOption* option);

template<>
void CastString::operation(const ku_string_t& input, list_entry_t& result,
    ValueVector* resultVector, uint64_t rowToAdd, const CSVOption* option);

template<>
void CastString::operation(const ku_string_t& input, map_entry_t& result, ValueVector* resultVector,
    uint64_t rowToAdd, const CSVOption* option);

template<>
void CastString::operation(const ku_string_t& input, struct_entry_t& result,
    ValueVector* resultVector, uint64_t rowToAdd, const CSVOption* option);

template<>
void CastString::operation(const ku_string_t& input, union_entry_t& result,
    ValueVector* resultVector, uint64_t rowToAdd, const CSVOption* option);

} // namespace function
} // namespace lbug
