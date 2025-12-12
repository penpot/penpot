#include "function/cast/functions/cast_string_non_nested_functions.h"

#include "common/constants.h"
#include "common/types/date_t.h"
#include "common/types/interval_t.h"
#include "common/types/timestamp_t.h"
#include "common/types/uuid.h"
#include "function/cast/functions/numeric_limits.h"
#include "re2.h"

namespace lbug {
namespace function {

bool tryCastToBool(const char* input, uint64_t len, bool& result) {
    StringUtils::removeCStringWhiteSpaces(input, len);

    switch (len) {
    case 1: {
        char c = std::tolower(*input);
        if (c == 't' || c == '1') {
            result = true;
            return true;
        } else if (c == 'f' || c == '0') {
            result = false;
            return true;
        }
        return false;
    }
    case 4: {
        auto t = std::tolower(input[0]);
        auto r = std::tolower(input[1]);
        auto u = std::tolower(input[2]);
        auto e = std::tolower(input[3]);
        if (t == 't' && r == 'r' && u == 'u' && e == 'e') {
            result = true;
            return true;
        }
        return false;
    }
    case 5: {
        auto f = std::tolower(input[0]);
        auto a = std::tolower(input[1]);
        auto l = std::tolower(input[2]);
        auto s = std::tolower(input[3]);
        auto e = std::tolower(input[4]);
        if (f == 'f' && a == 'a' && l == 'l' && s == 's' && e == 'e') {
            result = false;
            return true;
        }
        return false;
    }
    default:
        return false;
    }
}

void castStringToBool(const char* input, uint64_t len, bool& result) {
    if (!tryCastToBool(input, len, result)) {
        throw ConversionException{
            stringFormat("Value {} is not a valid boolean", std::string{input, (size_t)len})};
    }
}

template<>
bool TryCastStringToTimestamp::tryCast<timestamp_ns_t>(const char* input, uint64_t len,
    timestamp_t& result) {
    if (!Timestamp::tryConvertTimestamp(input, len, result)) {
        return false;
    }
    result = Timestamp::getEpochNanoSeconds(result);
    return true;
}

template<>
bool TryCastStringToTimestamp::tryCast<timestamp_ms_t>(const char* input, uint64_t len,
    timestamp_t& result) {
    if (!Timestamp::tryConvertTimestamp(input, len, result)) {
        return false;
    }
    result = Timestamp::getEpochMilliSeconds(result);
    return true;
}

template<>
bool TryCastStringToTimestamp::tryCast<timestamp_sec_t>(const char* input, uint64_t len,
    timestamp_t& result) {
    if (!Timestamp::tryConvertTimestamp(input, len, result)) {
        return false;
    }
    result = Timestamp::getEpochSeconds(result);
    return true;
}

static bool isDate(std::string_view str) {
    return RE2::FullMatch(str, Date::regexPattern());
}

static bool isUUID(std::string_view str) {
    return RE2::FullMatch(str, UUID::regexPattern());
}

static bool isInterval(std::string_view str) {
    return RE2::FullMatch(str, Interval::regexPattern1()) ||
           RE2::FullMatch(str, Interval::regexPattern2());
}

static LogicalType inferMapOrStruct(std::string_view str) {
    auto split = StringUtils::smartSplit(str.substr(1, str.size() - 2), ',');
    bool isMap = true, isStruct = true; // Default match to map if both are true
    for (auto& ele : split) {
        if (StringUtils::smartSplit(ele, '=', 2).size() != 2) {
            isMap = false;
        }
        if (StringUtils::smartSplit(ele, ':', 2).size() != 2) {
            isStruct = false;
        }
    }
    if (isMap) {
        auto childKeyType = LogicalType::ANY();
        auto childValueType = LogicalType::ANY();
        for (auto& ele : split) {
            auto split = StringUtils::smartSplit(ele, '=', 2);
            auto& key = split[0];
            auto& value = split[1];
            childKeyType =
                LogicalTypeUtils::combineTypes(childKeyType, inferMinimalTypeFromString(key));
            childValueType =
                LogicalTypeUtils::combineTypes(childValueType, inferMinimalTypeFromString(value));
        }
        return LogicalType::MAP(std::move(childKeyType), std::move(childValueType));
    } else if (isStruct) {
        std::vector<StructField> fields;
        for (auto& ele : split) {
            auto split = StringUtils::smartSplit(ele, ':', 2);
            auto fieldKey = StringUtils::ltrim(StringUtils::rtrim(split[0]));
            if (fieldKey.size() > 0 && fieldKey.front() == '\'') {
                fieldKey = fieldKey.substr(1);
            }
            if (fieldKey.size() > 0 && fieldKey.back() == '\'') {
                fieldKey = fieldKey.substr(0, fieldKey.size() - 1);
            }
            auto fieldType = inferMinimalTypeFromString(split[1]);
            fields.emplace_back(std::string(fieldKey), std::move(fieldType));
        }
        return LogicalType::STRUCT(std::move(fields));
    } else {
        return LogicalType::STRING();
    }
}

LogicalType inferMinimalTypeFromString(const std::string& str) {
    return inferMinimalTypeFromString(std::string_view(str));
}

static RE2& boolPattern() {
    static RE2 retval("(?i)(T|F|TRUE|FALSE)");
    return retval;
}
static RE2& intPattern() {
    static RE2 retval("(-?0)|(-?[1-9]\\d*)");
    return retval;
}
static RE2& realPattern() {
    static RE2 retval("(\\+|-)?(0|[1-9]\\d*)?\\.(\\d*)");
    return retval;
}

bool isAnyType(std::string_view cpy) {
    return cpy.size() == 0 || StringUtils::caseInsensitiveEquals(cpy, "NULL") ||
           StringUtils::caseInsensitiveEquals(cpy, "NAN");
}

bool isINF(std::string_view cpy) {
    return StringUtils::caseInsensitiveEquals(cpy, "INF") ||
           StringUtils::caseInsensitiveEquals(cpy, "+INF") ||
           StringUtils::caseInsensitiveEquals(cpy, "-INF") ||
           StringUtils::caseInsensitiveEquals(cpy, "INFINITY") ||
           StringUtils::caseInsensitiveEquals(cpy, "+INFINITY") ||
           StringUtils::caseInsensitiveEquals(cpy, "-INFINITY");
}

LogicalType inferMinimalTypeFromString(std::string_view str) {
    constexpr char array_begin = common::CopyConstants::DEFAULT_CSV_LIST_BEGIN_CHAR;
    constexpr char array_end = common::CopyConstants::DEFAULT_CSV_LIST_END_CHAR;
    auto cpy = StringUtils::ltrim(StringUtils::rtrim(str));
    // Check special double literals
    if (isINF(cpy)) {
        return LogicalType::DOUBLE();
    }
    // Any
    if (isAnyType(cpy)) {
        return LogicalType::ANY();
    }
    // Boolean
    if (RE2::FullMatch(cpy, boolPattern())) {
        return LogicalType::BOOL();
    }
    // The reason we're not going to try to match to a minimal width integer
    // is because if we're infering the type of integer from a sequence of
    // increasing integers, we're bound to underestimate the width
    // if we only sniff the first few elements; a rather common occurrence.

    // integer
    if (RE2::FullMatch(cpy, intPattern())) {
        if (cpy.size() >= 1 + NumericLimits<int128_t>::maxNumDigits()) {
            return LogicalType::DOUBLE();
        }
        int128_t int128val = 0;
        uint128_t uint128val = 0;
        if (trySimpleIntegerCast<int128_t, true>(cpy.data(), cpy.length(), int128val)) {
            if (NumericLimits<int64_t>::isInBounds(int128val)) {
                return LogicalType::INT64();
            }
            KU_ASSERT(NumericLimits<int128_t>::isInBounds(int128val));
            return LogicalType::INT128();
        } else if (trySimpleIntegerCast<uint128_t, false>(cpy.data(), cpy.length(), uint128val)) {
            return LogicalType::UINT128();
        }
        return LogicalType::STRING();
    }
    // Real value checking
    if (RE2::FullMatch(cpy, realPattern())) {
        if (cpy[0] == '-') {
            cpy = cpy.substr(1);
        }
        if (cpy.size() <= DECIMAL_PRECISION_LIMIT) {
            auto decimalPoint = cpy.find('.');
            KU_ASSERT(decimalPoint != std::string::npos);
            return LogicalType::DECIMAL(cpy.size() - 1, cpy.size() - decimalPoint - 1);
        } else {
            return LogicalType::DOUBLE();
        }
    }
    // date
    if (isDate(cpy)) {
        return LogicalType::DATE();
    }
    // It might just be quicker to try cast to timestamp.
    timestamp_t tmp;
    if (common::Timestamp::tryConvertTimestamp(cpy.data(), cpy.length(), tmp)) {
        return LogicalType::TIMESTAMP();
    }

    // UUID
    if (isUUID(cpy)) {
        return LogicalType::UUID();
    }

    // interval checking
    if (isInterval(cpy)) {
        return LogicalType::INTERVAL();
    }

    // array_begin and array_end are constants
    if (cpy.front() == array_begin && cpy.back() == array_end) {
        auto split = StringUtils::smartSplit(cpy.substr(1, cpy.size() - 2), ',');
        auto childType = LogicalType::ANY();
        for (auto& ele : split) {
            childType = LogicalTypeUtils::combineTypes(childType, inferMinimalTypeFromString(ele));
        }
        return LogicalType::LIST(std::move(childType));
    }

    if (cpy.front() == '{' && cpy.back() == '}') {
        return inferMapOrStruct(cpy);
    }

    return LogicalType::STRING();
}

} // namespace function
} // namespace lbug
