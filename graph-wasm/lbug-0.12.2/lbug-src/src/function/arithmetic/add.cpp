#include "function/arithmetic/add.h"

#include "common/exception/overflow.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

// reference from duckDB add.cpp
template<class SRC_TYPE, class DST_TYPE>
static inline bool addInPlaceWithOverflowCheck(SRC_TYPE left, SRC_TYPE right, SRC_TYPE& result) {
    DST_TYPE uresult;
    uresult = static_cast<DST_TYPE>(left) + static_cast<DST_TYPE>(right);
    if (uresult < NumericLimits<SRC_TYPE>::minimum() ||
        uresult > NumericLimits<SRC_TYPE>::maximum()) {
        return false;
    }
    result = static_cast<SRC_TYPE>(uresult);
    return true;
}

struct AddInPlace {
    template<class A, class B, class R>
    static inline bool operation(A& left, B& right, R& result);
};

template<>
bool inline AddInPlace::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    return addInPlaceWithOverflowCheck<uint8_t, uint16_t>(left, right, result);
}

template<>
bool inline AddInPlace::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    return addInPlaceWithOverflowCheck<uint16_t, uint32_t>(left, right, result);
}

template<>
bool inline AddInPlace::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    return addInPlaceWithOverflowCheck<uint32_t, uint64_t>(left, right, result);
}

template<>
bool AddInPlace::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (NumericLimits<uint64_t>::maximum() - left < right) {
        return false;
    }
    return addInPlaceWithOverflowCheck<uint64_t, uint64_t>(left, right, result);
}

template<>
bool inline AddInPlace::operation(int8_t& left, int8_t& right, int8_t& result) {
    return addInPlaceWithOverflowCheck<int8_t, int16_t>(left, right, result);
}

template<>
bool inline AddInPlace::operation(int16_t& left, int16_t& right, int16_t& result) {
    return addInPlaceWithOverflowCheck<int16_t, int32_t>(left, right, result);
}

template<>
bool inline AddInPlace::operation(int32_t& left, int32_t& right, int32_t& result) {
    return addInPlaceWithOverflowCheck<int32_t, int64_t>(left, right, result);
}

template<>
bool AddInPlace::operation(int64_t& left, int64_t& right, int64_t& result) {
#if (__GNUC__ >= 5) || defined(__clang__)
    if (__builtin_add_overflow(left, right, &result)) {
        return false;
    }
#else
    // https://blog.regehr.org/archives/1139
    int64_t tmp = int64_t((uint64_t)left + (uint64_t)right);
    if ((left < 0 && right < 0 && tmp >= 0) || (left >= 0 && right >= 0 && tmp < 0)) {
        return false;
    }
    result = std::move(tmp);
#endif
    return true;
}

template<>
void Add::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within UINT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within UINT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within UINT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within UINT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(int8_t& left, int8_t& right, int8_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within INT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(int16_t& left, int16_t& right, int16_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within INT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(int32_t& left, int32_t& right, int32_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within INT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Add::operation(int64_t& left, int64_t& right, int64_t& result) {
    if (!AddInPlace::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} + {} is not within INT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

} // namespace function
} // namespace lbug
