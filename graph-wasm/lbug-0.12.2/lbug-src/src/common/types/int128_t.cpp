#include "common/types/int128_t.h"

#include <cmath>
#include <cstdint>

#include "common/exception/runtime.h"
#include "common/numeric_utils.h"
#include "common/type_utils.h"
#include "common/types/uint128_t.h"
#include "function/cast/functions/numeric_limits.h"
#include "function/hash/hash_functions.h"
#include <bit>

namespace lbug::common {

static uint8_t positiveInt128BitsAmount(int128_t input) {
    if (input.high) {
        return 128 - std::countl_zero((uint64_t)input.high);
    } else {
        return 64 - std::countl_zero(input.low);
    }
}

static bool positiveInt128IsBitSet(int128_t input, uint8_t bit) {
    if (bit < 64) {
        return input.low & (1ULL << uint64_t(bit));
    } else {
        return input.high & (1ULL << uint64_t(bit - 64));
    }
}

int128_t positiveInt128LeftShift(int128_t lhs, uint32_t amount) {
    int128_t result{};
    result.low = lhs.low << amount;
    result.high = (lhs.high << amount) + (lhs.low >> (64 - amount));
    return result;
}

int128_t Int128_t::divModPositive(int128_t lhs, uint64_t rhs, uint64_t& remainder) {
    int128_t result{0};
    remainder = 0;

    for (uint8_t i = positiveInt128BitsAmount(lhs); i > 0; i--) {
        result = positiveInt128LeftShift(result, 1);
        remainder <<= 1;
        if (positiveInt128IsBitSet(lhs, i - 1)) {
            remainder++;
        }
        if (remainder >= rhs) {
            remainder -= rhs;
            result.low++;
            if (result.low == 0) {
                result.high++;
            }
        }
    }
    return result;
}

std::string Int128_t::toString(int128_t input) {
    bool isMin = (input.high == INT64_MIN && input.low == 0);
    bool negative = input.high < 0;

    if (isMin) {

        uint64_t remainder = 0;
        int128_t quotient = divModPositive(input, 10, remainder);

        std::string result = toString(quotient);

        result += static_cast<char>('0' + remainder);

        return "-" + result;
    }

    if (negative) {
        negateInPlace(input);
    }

    std::string result;
    uint64_t remainder = 0;

    while (input.high != 0 || input.low != 0) {
        input = divModPositive(input, 10, remainder);
        result = std::string(1, '0' + remainder) + std::move(result);
    }

    if (result.empty()) {
        result = "0";
    }

    return negative ? "-" + result : result;
}

bool Int128_t::addInPlace(int128_t& lhs, int128_t rhs) {
    bool lhsPositive = lhs.high >= 0;
    bool rhsPositive = rhs.high >= 0;
    int overflow = lhs.low + rhs.low < lhs.low;
    if (rhs.high >= 0) {
        if (lhs.high > INT64_MAX - rhs.high - overflow) {
            return false;
        }
        lhs.high = lhs.high + rhs.high + overflow;
    } else {
        if (lhs.high < INT64_MIN - rhs.high - overflow) {
            return false;
        }
        lhs.high = lhs.high + rhs.high + overflow;
    }
    lhs.low += rhs.low;
    if (lhsPositive && rhsPositive && lhs.high == INT64_MIN && lhs.low == 0) {
        return false;
    }
    return true;
}

bool Int128_t::subInPlace(int128_t& lhs, int128_t rhs) {
    int underflow = lhs.low - rhs.low > lhs.low;
    if (rhs.high >= 0) {
        if (lhs.high < INT64_MIN + rhs.high + underflow) {
            return false;
        }
        lhs.high = lhs.high - rhs.high - underflow;
    } else {
        if (lhs.high > INT64_MIN && lhs.high - 1 >= INT64_MAX + rhs.high + underflow) {
            return false;
        }
        lhs.high = lhs.high - rhs.high - underflow;
    }
    lhs.low -= rhs.low;
    if (lhs.high == INT64_MIN && lhs.low == 0) {
        return false;
    }
    return true;
}

int128_t Int128_t::Add(int128_t lhs, const int128_t rhs) {
    if (!addInPlace(lhs, rhs)) {
        throw common::OverflowException("INT128 is out of range: cannot add.");
    }
    return lhs;
}

int128_t Int128_t::Sub(int128_t lhs, const int128_t rhs) {
    if (!subInPlace(lhs, rhs)) {
        throw common::OverflowException("INT128 is out of range: cannot subtract.");
    }
    return lhs;
}

bool Int128_t::tryMultiply(int128_t lhs, int128_t rhs, int128_t& result) {
    bool lhs_negative = lhs.high < 0;
    bool rhs_negative = rhs.high < 0;
    if (lhs_negative) {
        negateInPlace(lhs);
    }
    if (rhs_negative) {
        negateInPlace(rhs);
    }
#if ((__GNUC__ >= 5) || defined(__clang__)) && defined(__SIZEOF_INT128__)
    __uint128_t left = __uint128_t(lhs.low) + (__uint128_t(lhs.high) << 64);
    __uint128_t right = __uint128_t(rhs.low) + (__uint128_t(rhs.high) << 64);
    __uint128_t result_i128 = 0;
    if (__builtin_mul_overflow(left, right, &result_i128)) {
        return false;
    }
    auto high = uint64_t(result_i128 >> 64);
    if (high & 0x8000000000000000) {
        return false;
    }
    result.high = int64_t(high);
    result.low = uint64_t(result_i128 & 0xffffffffffffffff);
#else
    // Multiply code adapted from:
    // https://github.com/calccrypto/uint128_t/blob/master/uint128_t.cpp
    // License: https://github.com/calccrypto/uint128_t/blob/c%2B%2B11_14/LICENSE
    uint64_t top[4] = {uint64_t(lhs.high) >> 32, uint64_t(lhs.high) & 0xffffffff, lhs.low >> 32,
        lhs.low & 0xffffffff};
    uint64_t bottom[4] = {uint64_t(rhs.high) >> 32, uint64_t(rhs.high) & 0xffffffff, rhs.low >> 32,
        rhs.low & 0xffffffff};
    uint64_t products[4][4];

    // multiply each component of the values
    for (auto x = 0; x < 4; x++) {
        for (auto y = 0; y < 4; y++) {
            products[x][y] = top[x] * bottom[y];
        }
    }

    // if any of these products are set to a non-zero value, there is always an overflow
    if (products[0][0] || products[0][1] || products[0][2] || products[1][0] || products[2][0] ||
        products[1][1]) {
        return false;
    }
    // if the high bits of any of these are set, there is always an overflow
    if ((products[0][3] & 0xffffffff80000000) || (products[1][2] & 0xffffffff80000000) ||
        (products[2][1] & 0xffffffff80000000) || (products[3][0] & 0xffffffff80000000)) {
        return false;
    }

    // otherwise we merge the result of the different products together in-order

    // first row
    uint64_t fourth32 = (products[3][3] & 0xffffffff);
    uint64_t third32 = (products[3][2] & 0xffffffff) + (products[3][3] >> 32);
    uint64_t second32 = (products[3][1] & 0xffffffff) + (products[3][2] >> 32);
    uint64_t first32 = (products[3][0] & 0xffffffff) + (products[3][1] >> 32);

    // second row
    third32 += (products[2][3] & 0xffffffff);
    second32 += (products[2][2] & 0xffffffff) + (products[2][3] >> 32);
    first32 += (products[2][1] & 0xffffffff) + (products[2][2] >> 32);

    // third row
    second32 += (products[1][3] & 0xffffffff);
    first32 += (products[1][2] & 0xffffffff) + (products[1][3] >> 32);

    // fourth row
    first32 += (products[0][3] & 0xffffffff);

    // move carry to next digit
    third32 += fourth32 >> 32;
    second32 += third32 >> 32;
    first32 += second32 >> 32;

    // check if the combination of the different products resulted in an overflow
    if (first32 & 0xffffff80000000) {
        return false;
    }

    // remove carry from current digit
    fourth32 &= 0xffffffff;
    third32 &= 0xffffffff;
    second32 &= 0xffffffff;
    first32 &= 0xffffffff;

    // combine components
    result.low = (third32 << 32) | fourth32;
    result.high = (first32 << 32) | second32;
#endif
    if (lhs_negative ^ rhs_negative) {
        negateInPlace(result);
    }
    return true;
}

int128_t Int128_t::Mul(int128_t lhs, int128_t rhs) {
    int128_t result{};
    if (!tryMultiply(lhs, rhs, result)) {
        throw common::OverflowException("INT128 is out of range: cannot multiply.");
    }
    return result;
}

int128_t Int128_t::divMod(int128_t lhs, int128_t rhs, int128_t& remainder) {
    bool lhs_negative = lhs.high < 0;
    bool rhs_negative = rhs.high < 0;
    if (lhs_negative) {
        negateInPlace(lhs);
    }
    if (rhs_negative) {
        negateInPlace(rhs);
    }

    // divMod code adapted from:
    // https://github.com/calccrypto/uint128_t/blob/master/uint128_t.cpp
    // License: https://github.com/calccrypto/uint128_t/blob/c%2B%2B11_14/LICENSE
    // initialize the result and remainder to 0
    int128_t div_result{0};
    remainder.low = 0;
    remainder.high = 0;

    // now iterate over the amount of bits that are set in the LHS
    for (uint8_t x = positiveInt128BitsAmount(lhs); x > 0; x--) {
        // left-shift the current result and remainder by 1
        div_result = positiveInt128LeftShift(div_result, 1);
        remainder = positiveInt128LeftShift(remainder, 1);

        // we get the value of the bit at position X, where position 0 is the least-significant bit
        if (positiveInt128IsBitSet(lhs, x - 1)) {
            // increment the remainder
            addInPlace(remainder, 1);
        }
        if (greaterThanOrEquals(remainder, rhs)) {
            // the remainder has passed the division multiplier: add one to the divide result
            remainder = Sub(remainder, rhs);
            addInPlace(div_result, 1);
        }
    }
    if (lhs_negative ^ rhs_negative) {
        negateInPlace(div_result);
    }
    if (lhs_negative) {
        negateInPlace(remainder);
    }
    return div_result;
}

int128_t Int128_t::Div(int128_t lhs, int128_t rhs) {
    if (rhs.high == 0 && rhs.low == 0) {
        throw common::RuntimeException("Divide by zero.");
    }
    int128_t remainder{};
    return divMod(lhs, rhs, remainder);
}

int128_t Int128_t::Mod(int128_t lhs, int128_t rhs) {
    if (rhs.high == 0 && rhs.low == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    int128_t result{};
    divMod(lhs, rhs, result);
    return result;
}

int128_t Int128_t::Xor(int128_t lhs, int128_t rhs) {
    int128_t result{lhs.low ^ rhs.low, lhs.high ^ rhs.high};
    return result;
}

int128_t Int128_t::BinaryAnd(int128_t lhs, int128_t rhs) {
    int128_t result{lhs.low & rhs.low, lhs.high & rhs.high};
    return result;
}

int128_t Int128_t::BinaryOr(int128_t lhs, int128_t rhs) {
    int128_t result{lhs.low | rhs.low, lhs.high | rhs.high};
    return result;
}

int128_t Int128_t::BinaryNot(int128_t val) {
    return int128_t{~val.low, ~val.high};
}

int128_t Int128_t::LeftShift(int128_t lhs, int amount) {
    // adapted from
    // https://github.com/abseil/abseil-cpp/blob/master/absl/numeric/int128.h
    return amount >= 64 ? int128_t(0, lhs.low << (amount - 64)) :
           amount == 0  ? lhs :
                          int128_t{lhs.low << amount,
                             (lhs.high << amount) |
                                 (numeric_utils::makeValueSigned(lhs.low >> (64 - amount)))};
}

int128_t Int128_t::RightShift(int128_t lhs, int amount) {
    // adapted from
    // https://github.com/abseil/abseil-cpp/blob/master/absl/numeric/int128.h
    return amount >= 64 ?
               // we shift the high value regardless for sign extension
               int128_t(lhs.high >> (amount - 64), lhs.high >> 63) :
               amount == 0 ?
               lhs :
               int128_t((lhs.low >> amount) | (lhs.high << (64 - amount)), lhs.high >> amount);
}

//===============================================================================================
// Cast operation
//===============================================================================================
template<class DST, bool SIGNED = true>
bool TryCastInt128Template(int128_t input, DST& result) {
    switch (input.high) {
    case 0:
        if (input.low <= uint64_t(function::NumericLimits<DST>::maximum())) {
            result = static_cast<DST>(input.low);
            return true;
        }
        break;
    case -1:
        if constexpr (!SIGNED) {
            throw common::OverflowException(
                "Cast failed. Cannot cast " + Int128_t::toString(input) + " to unsigned type.");
        }
        if (input.low >= function::NumericLimits<uint64_t>::maximum() -
                             uint64_t(function::NumericLimits<DST>::maximum())) {
            result = -DST(function::NumericLimits<uint64_t>::maximum() - input.low) - 1;
            return true;
        }
        break;
    default:
        break;
    }
    return false;
}
// we can use the above template if we can get max using something like DST.max

template<>
bool Int128_t::tryCast(int128_t input, int8_t& result) {
    return TryCastInt128Template<int8_t>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, int16_t& result) {
    return TryCastInt128Template<int16_t>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, int32_t& result) {
    return TryCastInt128Template<int32_t>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, int64_t& result) {
    return TryCastInt128Template<int64_t>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, uint8_t& result) {
    return TryCastInt128Template<uint8_t, false>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, uint16_t& result) {
    return TryCastInt128Template<uint16_t, false>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, uint32_t& result) {
    return TryCastInt128Template<uint32_t, false>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, uint64_t& result) {
    return TryCastInt128Template<uint64_t, false>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, uint128_t& result) {
    if (input.high < 0) {
        return false;
    }
    result.low = input.low;
    result.high = uint64_t(input.high);
    return true;
}

template<>
bool Int128_t::tryCast(int128_t input, float& result) {
    double temp_res = NAN;
    tryCast(input, temp_res);
    result = static_cast<float>(temp_res);
    return true;
}

template<class REAL_T>
bool CastInt128ToFloating(int128_t input, REAL_T& result) {
    switch (input.high) {
    case -1:
        result = -REAL_T(function::NumericLimits<uint64_t>::maximum() - input.low) - 1;
        break;
    default:
        result = REAL_T(input.high) * REAL_T(function::NumericLimits<uint64_t>::maximum()) +
                 REAL_T(input.low);
        break;
    }
    return true;
}

template<>
bool Int128_t::tryCast(int128_t input, double& result) {
    return CastInt128ToFloating<double>(input, result);
}

template<>
bool Int128_t::tryCast(int128_t input, long double& result) {
    return CastInt128ToFloating<long double>(input, result);
}

template<class SRC>
int128_t tryCastToTemplate(SRC value) {
    int128_t result{};
    result.low = (uint64_t)value;
    result.high = (value < 0) * -1;
    return result;
}

template<>
bool Int128_t::tryCastTo(int8_t value, int128_t& result) {
    result = tryCastToTemplate<int8_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(int16_t value, int128_t& result) {
    result = tryCastToTemplate<int16_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(int32_t value, int128_t& result) {
    result = tryCastToTemplate<int32_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(int64_t value, int128_t& result) {
    result = tryCastToTemplate<int64_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(uint8_t value, int128_t& result) {
    result = tryCastToTemplate<uint8_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(uint16_t value, int128_t& result) {
    result = tryCastToTemplate<uint16_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(uint32_t value, int128_t& result) {
    result = tryCastToTemplate<uint32_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(uint64_t value, int128_t& result) {
    result = tryCastToTemplate<uint64_t>(value);
    return true;
}

template<>
bool Int128_t::tryCastTo(int128_t value, int128_t& result) {
    result = value;
    return true;
}

template<>
bool Int128_t::tryCastTo(float value, int128_t& result) {
    return tryCastTo(double(value), result);
}

template<class REAL_T>
bool castFloatingToInt128(REAL_T value, int128_t& result) {
    // TODO: Maybe need to add func isFinite in value.h to see if every type is finite.
    if (value <= -170141183460469231731687303715884105728.0 ||
        value >= 170141183460469231731687303715884105727.0) {
        return false;
    }
    bool negative = value < 0;
    if (negative) {
        value = -value;
    }
    value = std::nearbyint(value);
    result.low = (uint64_t)fmod(value, REAL_T(function::NumericLimits<uint64_t>::maximum()));
    result.high = (uint64_t)(value / REAL_T(function::NumericLimits<uint64_t>::maximum()));
    if (negative) {
        Int128_t::negateInPlace(result);
    }
    return true;
}

template<>
bool Int128_t::tryCastTo(double value, int128_t& result) {
    return castFloatingToInt128<double>(value, result);
}

template<>
bool Int128_t::tryCastTo(long double value, int128_t& result) {
    return castFloatingToInt128<long double>(value, result);
}
//===============================================================================================

template<NumericTypes T>
void constructInt128Template(T value, int128_t& result) {
    int128_t casted = Int128_t::castTo(value);
    result.low = casted.low;
    result.high = casted.high;
}

int128_t::int128_t(int64_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(int32_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(int16_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(int8_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(uint64_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(uint32_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(uint16_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(uint8_t value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(double value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

int128_t::int128_t(float value) { // NOLINT: fields are constructed by the template
    constructInt128Template(value, *this);
}

//============================================================================================
bool operator==(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::equals(lhs, rhs);
}

bool operator!=(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::notEquals(lhs, rhs);
}

bool operator>(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::greaterThan(lhs, rhs);
}

bool operator>=(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::greaterThanOrEquals(lhs, rhs);
}

bool operator<(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::lessThan(lhs, rhs);
}

bool operator<=(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::lessThanOrEquals(lhs, rhs);
}

int128_t int128_t::operator-() const {
    return Int128_t::negate(*this);
}

// support for operations like (int32_t)x + (int128_t)y

int128_t operator+(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::Add(lhs, rhs);
}
int128_t operator-(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::Sub(lhs, rhs);
}
int128_t operator*(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::Mul(lhs, rhs);
}
int128_t operator/(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::Div(lhs, rhs);
}
int128_t operator%(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::Mod(lhs, rhs);
}

int128_t operator^(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::Xor(lhs, rhs);
}

int128_t operator&(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::BinaryAnd(lhs, rhs);
}

int128_t operator|(const int128_t& lhs, const int128_t& rhs) {
    return Int128_t::BinaryOr(lhs, rhs);
}

int128_t operator~(const int128_t& val) {
    return Int128_t::BinaryNot(val);
}

int128_t operator<<(const int128_t& lhs, int amount) {
    return Int128_t::LeftShift(lhs, amount);
}

int128_t operator>>(const int128_t& lhs, int amount) {
    return Int128_t::RightShift(lhs, amount);
}

// inplace arithmetic operators
int128_t& int128_t::operator+=(const int128_t& rhs) {
    if (!Int128_t::addInPlace(*this, rhs)) {
        throw common::OverflowException("INT128 is out of range: cannot add in place.");
    }
    return *this;
}

int128_t& int128_t::operator*=(const int128_t& rhs) {
    *this = Int128_t::Mul(*this, rhs);
    return *this;
}

int128_t& int128_t::operator|=(const int128_t& rhs) {
    *this = Int128_t::BinaryOr(*this, rhs);
    return *this;
}

int128_t& int128_t::operator&=(const int128_t& rhs) {
    *this = Int128_t::BinaryAnd(*this, rhs);
    return *this;
}

template<class T>
static T NarrowCast(const int128_t& input) {
    return static_cast<T>(input.low);
}

int128_t::operator int64_t() const {
    return NarrowCast<int64_t>(*this);
}

int128_t::operator int32_t() const {
    return NarrowCast<int32_t>(*this);
}

int128_t::operator int16_t() const {
    return NarrowCast<int16_t>(*this);
}

int128_t::operator int8_t() const {
    return NarrowCast<int8_t>(*this);
}

int128_t::operator uint64_t() const {
    return NarrowCast<uint64_t>(*this);
}

int128_t::operator uint32_t() const {
    return NarrowCast<uint32_t>(*this);
}

int128_t::operator uint16_t() const {
    return NarrowCast<uint16_t>(*this);
}

int128_t::operator uint8_t() const {
    return NarrowCast<uint8_t>(*this);
}

int128_t::operator double() const {
    double result = NAN;
    if (!Int128_t::tryCast(*this, result)) { // LCOV_EXCL_START
        throw common::OverflowException(common::stringFormat("Value {} is not within DOUBLE range",
            common::TypeUtils::toString(*this)));
    } // LCOV_EXCL_STOP
    return result;
}

int128_t::operator float() const {
    float result = NAN;
    if (!Int128_t::tryCast(*this, result)) { // LCOV_EXCL_START
        throw common::OverflowException(common::stringFormat("Value {} is not within FLOAT range",
            common::TypeUtils::toString(*this)));
    } // LCOV_EXCL_STOP
    return result;
}

int128_t::operator uint128_t() const {
    uint128_t result{};
    if (!Int128_t::tryCast(*this, result)) {
        throw common::OverflowException(common::stringFormat(
            "Cannot cast negative INT128 value {} to UINT128", common::TypeUtils::toString(*this)));
    }
    return result;
}

} // namespace lbug::common

std::size_t std::hash<lbug::common::int128_t>::operator()(
    const lbug::common::int128_t& v) const noexcept {
    lbug::common::hash_t hash = 0;
    lbug::function::Hash::operation(v, hash);
    return hash;
}
