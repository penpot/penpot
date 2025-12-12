#include "function/arithmetic/abs.h"

#include "common/exception/overflow.h"
#include "common/string_format.h"
#include "common/type_utils.h"
#include "function/cast/functions/numeric_limits.h"

namespace lbug {
namespace function {

// reference from duckDB arithmetic.cpp
template<class SRC_TYPE>
static inline bool AbsInPlaceWithOverflowCheck(SRC_TYPE input, SRC_TYPE& result) {
    if (input == NumericLimits<SRC_TYPE>::minimum()) {
        return false;
    }
    result = std::abs(input);
    return true;
}

struct AbsInPlace {
    template<class T>
    static inline bool operation(T& input, T& result);
};

template<>
bool inline AbsInPlace::operation(int8_t& input, int8_t& result) {
    return AbsInPlaceWithOverflowCheck<int8_t>(input, result);
}

template<>
bool inline AbsInPlace::operation(int16_t& input, int16_t& result) {
    return AbsInPlaceWithOverflowCheck<int16_t>(input, result);
}

template<>
bool inline AbsInPlace::operation(int32_t& input, int32_t& result) {
    return AbsInPlaceWithOverflowCheck<int32_t>(input, result);
}

template<>
bool AbsInPlace::operation(int64_t& input, int64_t& result) {
    return AbsInPlaceWithOverflowCheck<int64_t>(input, result);
}

template<>
void Abs::operation(int8_t& input, int8_t& result) {
    if (!AbsInPlace::operation(input, result)) {
        throw common::OverflowException{
            common::stringFormat("Cannot take the absolute value of {} within INT8 range.",
                common::TypeUtils::toString(input))};
    }
}

template<>
void Abs::operation(int16_t& input, int16_t& result) {
    if (!AbsInPlace::operation(input, result)) {
        throw common::OverflowException{
            common::stringFormat("Cannot take the absolute value of {} within INT16 range.",
                common::TypeUtils::toString(input))};
    }
}

template<>
void Abs::operation(int32_t& input, int32_t& result) {
    if (!AbsInPlace::operation(input, result)) {
        throw common::OverflowException{
            common::stringFormat("Cannot take the absolute value of {} within INT32 range.",
                common::TypeUtils::toString(input))};
    }
}

template<>
void Abs::operation(int64_t& input, int64_t& result) {
    if (!AbsInPlace::operation(input, result)) {
        throw common::OverflowException{
            common::stringFormat("Cannot take the absolute value of {} within INT64 range.",
                common::TypeUtils::toString(input))};
    }
}

template<>
void Abs::operation(common::int128_t& input, common::int128_t& result) {
    result = input < 0 ? -input : input;
}

} // namespace function
} // namespace lbug
