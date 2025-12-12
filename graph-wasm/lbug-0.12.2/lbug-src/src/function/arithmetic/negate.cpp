#include "function/arithmetic/negate.h"

#include "common/exception/overflow.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

// reference from duckDB arithmetic.cpp
template<class SRC_TYPE>
static inline bool NegateInPlaceWithOverflowCheck(SRC_TYPE input, SRC_TYPE& result) {
    if (input == NumericLimits<SRC_TYPE>::minimum()) {
        return false;
    }
    result = -input;
    return true;
}

struct NegateInPlace {
    template<class T>
    static inline bool operation(T& input, T& result);
};

template<>
bool inline NegateInPlace::operation(int8_t& input, int8_t& result) {
    return NegateInPlaceWithOverflowCheck<int8_t>(input, result);
}

template<>
bool inline NegateInPlace::operation(int16_t& input, int16_t& result) {
    return NegateInPlaceWithOverflowCheck<int16_t>(input, result);
}

template<>
bool inline NegateInPlace::operation(int32_t& input, int32_t& result) {
    return NegateInPlaceWithOverflowCheck<int32_t>(input, result);
}

template<>
bool NegateInPlace::operation(int64_t& input, int64_t& result) {
    return NegateInPlaceWithOverflowCheck<int64_t>(input, result);
}

template<>
void Negate::operation(int8_t& input, int8_t& result) {
    if (!NegateInPlace::operation(input, result)) {
        throw common::OverflowException{common::stringFormat(
            "Value {} cannot be negated within INT8 range.", common::TypeUtils::toString(input))};
    }
}

template<>
void Negate::operation(int16_t& input, int16_t& result) {
    if (!NegateInPlace::operation(input, result)) {
        throw common::OverflowException{common::stringFormat(
            "Value {} cannot be negated within INT16 range.", common::TypeUtils::toString(input))};
    }
}

template<>
void Negate::operation(int32_t& input, int32_t& result) {
    if (!NegateInPlace::operation(input, result)) {
        throw common::OverflowException{common::stringFormat(
            "Value {} cannot be negated within INT32 range.", common::TypeUtils::toString(input))};
    }
}

template<>
void Negate::operation(int64_t& input, int64_t& result) {
    if (!NegateInPlace::operation(input, result)) {
        throw common::OverflowException{common::stringFormat(
            "Value {} cannot be negated within INT64 range.", common::TypeUtils::toString(input))};
    }
}

} // namespace function
} // namespace lbug
