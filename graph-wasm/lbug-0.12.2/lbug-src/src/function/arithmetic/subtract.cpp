#include "function/arithmetic/subtract.h"

#include "common/exception/overflow.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

// reference from duckDB subtract.cpp
template<class SRC_TYPE, class DST_TYPE>
static inline bool SubtractInPlaceWithOverflowCheck(SRC_TYPE left, SRC_TYPE right,
    SRC_TYPE& result) {
    DST_TYPE uresult;
    uresult = static_cast<DST_TYPE>(left) - static_cast<DST_TYPE>(right);
    if (uresult < NumericLimits<SRC_TYPE>::minimum() ||
        uresult > NumericLimits<SRC_TYPE>::maximum()) {
        return false;
    }
    result = static_cast<SRC_TYPE>(uresult);
    return true;
}

struct SubtractInPlace {
    template<class A, class B, class R>
    static inline bool operation(A& left, B& right, R& result);
};

template<>
bool inline SubtractInPlace::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    if (right > left) {
        return false;
    }
    return SubtractInPlaceWithOverflowCheck<uint8_t, uint16_t>(left, right, result);
}

template<>
bool inline SubtractInPlace::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    if (right > left) {
        return false;
    }
    return SubtractInPlaceWithOverflowCheck<uint16_t, uint32_t>(left, right, result);
}

template<>
bool inline SubtractInPlace::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    if (right > left) {
        return false;
    }
    return SubtractInPlaceWithOverflowCheck<uint32_t, uint64_t>(left, right, result);
}

template<>
bool SubtractInPlace::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (right > left) {
        return false;
    }
    return SubtractInPlaceWithOverflowCheck<uint64_t, uint64_t>(left, right, result);
}

template<>
bool inline SubtractInPlace::operation(int8_t& left, int8_t& right, int8_t& result) {
    return SubtractInPlaceWithOverflowCheck<int8_t, int16_t>(left, right, result);
}

template<>
bool inline SubtractInPlace::operation(int16_t& left, int16_t& right, int16_t& result) {
    return SubtractInPlaceWithOverflowCheck<int16_t, int32_t>(left, right, result);
}

template<>
bool inline SubtractInPlace::operation(int32_t& left, int32_t& right, int32_t& result) {
    return SubtractInPlaceWithOverflowCheck<int32_t, int64_t>(left, right, result);
}

template<>
bool SubtractInPlace::operation(int64_t& left, int64_t& right, int64_t& result) {
#if (__GNUC__ >= 5) || defined(__clang__)
    if (__builtin_sub_overflow(left, right, &result)) {
        return false;
    }
#else
    if (right < 0) {
        if (NumericLimits<int64_t>::maximum() + right < left) {
            return false;
        }
    } else {
        if (NumericLimits<int64_t>::minimum() + right > left) {
            return false;
        }
    }
    result = left - right;
#endif
    return true;
}

template<>
void Subtract::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within UINT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within UINT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within UINT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within UINT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(int8_t& left, int8_t& right, int8_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within INT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(int16_t& left, int16_t& right, int16_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within INT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(int32_t& left, int32_t& right, int32_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within INT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Subtract::operation(int64_t& left, int64_t& right, int64_t& result) {
    if (!SubtractInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} - {} is not within INT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

} // namespace function
} // namespace lbug
