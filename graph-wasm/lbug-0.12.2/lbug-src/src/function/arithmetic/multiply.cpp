#include "function/arithmetic/multiply.h"

#include "common/exception/overflow.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

// reference from duckDB multiply.cpp
template<class SRC_TYPE, class DST_TYPE>
static inline bool tryMultiplyWithOverflowCheck(SRC_TYPE left, SRC_TYPE right, SRC_TYPE& result) {
    DST_TYPE uresult;
    uresult = static_cast<DST_TYPE>(left) * static_cast<DST_TYPE>(right);
    if (uresult < NumericLimits<SRC_TYPE>::minimum() ||
        uresult > NumericLimits<SRC_TYPE>::maximum()) {
        return false;
    }
    result = static_cast<SRC_TYPE>(uresult);
    return true;
}

struct TryMultiply {
    template<class A, class B, class R>
    static inline bool operation(A& left, B& right, R& result);
};

template<>
bool inline TryMultiply::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    return tryMultiplyWithOverflowCheck<uint8_t, uint16_t>(left, right, result);
}

template<>
bool inline TryMultiply::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    return tryMultiplyWithOverflowCheck<uint16_t, uint32_t>(left, right, result);
}

template<>
bool inline TryMultiply::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    return tryMultiplyWithOverflowCheck<uint32_t, uint64_t>(left, right, result);
}

template<>
bool TryMultiply::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (left > right) {
        std::swap(left, right);
    }
    if (left > NumericLimits<uint32_t>::maximum()) {
        return false;
    }
    uint32_t c = right >> 32;
    uint32_t d = NumericLimits<uint32_t>::maximum() & right;
    uint64_t r = left * c;
    uint64_t s = left * d;
    if (r > NumericLimits<uint32_t>::maximum()) {
        return false;
    }
    r <<= 32;
    if (NumericLimits<uint64_t>::maximum() - s < r) {
        return false;
    }
    return tryMultiplyWithOverflowCheck<uint64_t, uint64_t>(left, right, result);
}

template<>
bool inline TryMultiply::operation(int8_t& left, int8_t& right, int8_t& result) {
    return tryMultiplyWithOverflowCheck<int8_t, int16_t>(left, right, result);
}

template<>
bool inline TryMultiply::operation(int16_t& left, int16_t& right, int16_t& result) {
    return tryMultiplyWithOverflowCheck<int16_t, int32_t>(left, right, result);
}

template<>
bool inline TryMultiply::operation(int32_t& left, int32_t& right, int32_t& result) {
    return tryMultiplyWithOverflowCheck<int32_t, int64_t>(left, right, result);
}

template<>
bool TryMultiply::operation(int64_t& left, int64_t& right, int64_t& result) {
#if (__GNUC__ >= 5) || defined(__clang__)
    if (__builtin_mul_overflow(left, right, &result)) {
        return false;
    }
#else
    if (left == std::numeric_limits<int64_t>::min()) {
        if (right == 0) {
            result = 0;
            return true;
        }
        if (right == 1) {
            result = left;
            return true;
        }
        return false;
    }
    if (right == std::numeric_limits<int64_t>::min()) {
        if (left == 0) {
            result = 0;
            return true;
        }
        if (left == 1) {
            result = right;
            return true;
        }
        return false;
    }
    uint64_t left_non_negative = uint64_t(std::abs(left));
    uint64_t right_non_negative = uint64_t(std::abs(right));
    // split values into 2 32-bit parts
    uint64_t left_high_bits = left_non_negative >> 32;
    uint64_t left_low_bits = left_non_negative & 0xffffffff;
    uint64_t right_high_bits = right_non_negative >> 32;
    uint64_t right_low_bits = right_non_negative & 0xffffffff;

    // check the high bits of both
    // the high bits define the overflow
    if (left_high_bits == 0) {
        if (right_high_bits != 0) {
            // only the right has high bits set
            // multiply the high bits of right with the low bits of left
            // multiply the low bits, and carry any overflow to the high bits
            // then check for any overflow
            auto low_low = left_low_bits * right_low_bits;
            auto low_high = left_low_bits * right_high_bits;
            auto high_bits = low_high + (low_low >> 32);
            if (high_bits & 0xffffff80000000) {
                // there is! abort
                return false;
            }
        }
    } else if (right_high_bits == 0) {
        // only the left has high bits set
        // multiply the high bits of left with the low bits of right
        // multiply the low bits, and carry any overflow to the high bits
        // then check for any overflow
        auto low_low = left_low_bits * right_low_bits;
        auto high_low = left_high_bits * right_low_bits;
        auto high_bits = high_low + (low_low >> 32);
        if (high_bits & 0xffffff80000000) {
            // there is! abort
            return false;
        }
    } else {
        // both left and right have high bits set: guaranteed overflow
        // abort!
        return false;
    }
    // now we know that there is no overflow, we can just perform the multiplication
    result = left * right;
#endif
    return true;
}

template<>
void Multiply::operation(uint8_t& left, uint8_t& right, uint8_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within UINT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(uint16_t& left, uint16_t& right, uint16_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within UINT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(uint32_t& left, uint32_t& right, uint32_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within UINT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(uint64_t& left, uint64_t& right, uint64_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within UINT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(int8_t& left, int8_t& right, int8_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within INT8 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(int16_t& left, int16_t& right, int16_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within INT16 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(int32_t& left, int32_t& right, int32_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within INT32 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

template<>
void Multiply::operation(int64_t& left, int64_t& right, int64_t& result) {
    if (!TryMultiply::operation(left, right, result)) {
        throw common::OverflowException{
            common::stringFormat("Value {} * {} is not within INT64 range.",
                common::TypeUtils::toString(left), common::TypeUtils::toString(right))};
    }
}

} // namespace function
} // namespace lbug
