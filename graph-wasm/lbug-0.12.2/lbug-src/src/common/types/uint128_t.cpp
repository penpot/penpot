#include "common/types/uint128_t.h"

#include <cmath>

#include "common/exception/runtime.h"
#include "common/type_utils.h"
#include "common/types/int128_t.h"
#include "function/cast/functions/numeric_limits.h"
#include "function/hash/hash_functions.h"
#include <bit>

namespace lbug::common {

static uint8_t uint128BitsAmount(uint128_t input) {
    if (input.high) {
        return 128 - std::countl_zero(input.high);
    } else {
        return 64 - std::countl_zero(input.low);
    }
}

static bool uint128IsBitSet(uint128_t input, uint8_t bit) {
    if (bit < 64) {
        return input.low & (1ULL << uint64_t(bit));
    } else {
        return input.high & (1ULL << uint64_t(bit - 64));
    }
}

uint128_t uint128LeftShift(uint128_t lhs, uint32_t amount) {
    uint128_t result{};
    result.low = lhs.low << amount;
    result.high = (lhs.high << amount) + (lhs.low >> (64 - amount));
    return result;
}

uint128_t UInt128_t::divModPositive(uint128_t lhs, uint64_t rhs, uint64_t& remainder) {
    uint128_t result{0};
    remainder = 0;

    for (uint8_t i = uint128BitsAmount(lhs); i > 0; i--) {
        result = uint128LeftShift(result, 1);
        remainder <<= 1;
        if (uint128IsBitSet(lhs, i - 1)) {
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

std::string UInt128_t::toString(uint128_t input) {
    std::string result;
    uint64_t remainder = 0;

    while (input.high != 0 || input.low != 0) {
        input = divModPositive(input, 10, remainder);
        result = std::string(1, '0' + remainder) + std::move(result);
    }

    if (result.empty()) {
        result = "0";
    }

    return result;
}

bool UInt128_t::addInPlace(uint128_t& lhs, uint128_t rhs) {
    int overflow = lhs.low + rhs.low < lhs.low;
    if (lhs.high > UINT64_MAX - rhs.high - overflow ||
        (rhs.high == UINT64_MAX &&
            lhs.high + overflow != 0)) { // need second condition in case the unsigned (UINT64_MAX -
                                         // rhs.high - overflow) evaluates to -1
        return false;
    }
    lhs.high = lhs.high + rhs.high + overflow;
    lhs.low += rhs.low;
    return true;
}

bool UInt128_t::subInPlace(uint128_t& lhs, uint128_t rhs) {
    // check if lhs > rhs; if so return false
    if (UInt128_t::lessThan(lhs, rhs)) {
        return false;
    }
    int underflow = lhs.low - rhs.low > lhs.low;
    lhs.high = lhs.high - rhs.high - underflow;
    lhs.low -= rhs.low;
    return true;
}

uint128_t UInt128_t::Add(uint128_t lhs, const uint128_t rhs) {
    if (!addInPlace(lhs, rhs)) {
        throw common::OverflowException("UINT128 is out of range: cannot add.");
    }
    return lhs;
}

uint128_t UInt128_t::Sub(uint128_t lhs, const uint128_t rhs) {
    if (!subInPlace(lhs, rhs)) {
        throw common::OverflowException("UINT128 is out of range: cannot subtract.");
    }
    return lhs;
}

bool UInt128_t::tryMultiply(uint128_t lhs, uint128_t rhs, uint128_t& result) {
#if ((__GNUC__ >= 5) || defined(__clang__)) && defined(__SIZEOF_INT128__)
    __uint128_t left = __uint128_t(lhs.low) + (__uint128_t(lhs.high) << 64);
    __uint128_t right = __uint128_t(rhs.low) + (__uint128_t(rhs.high) << 64);
    __uint128_t result_ui128 = 0;
    if (__builtin_mul_overflow(left, right, &result_ui128)) {
        return false;
    }
    result.high = uint64_t(result_ui128 >> 64);
    result.low = uint64_t(result_ui128 & 0xffffffffffffffff);
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
    if ((products[0][3] & 0xffffffff00000000) || (products[1][2] & 0xffffffff00000000) ||
        (products[2][1] & 0xffffffff00000000) || (products[3][0] & 0xffffffff00000000)) {
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
    if (first32 & 0xffffff00000000) {
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
    return true;
}

uint128_t UInt128_t::Mul(uint128_t lhs, uint128_t rhs) {
    uint128_t result{};
    if (!tryMultiply(lhs, rhs, result)) {
        throw common::OverflowException("UINT128 is out of range: cannot multiply.");
    }
    return result;
}

uint128_t UInt128_t::divMod(uint128_t lhs, uint128_t rhs, uint128_t& remainder) {
    // divMod code adapted from:
    // https://github.com/calccrypto/uint128_t/blob/master/uint128_t.cpp
    // License: https://github.com/calccrypto/uint128_t/blob/c%2B%2B11_14/LICENSE
    // initialize the result and remainder to 0
    uint128_t div_result{0};
    remainder.low = 0;
    remainder.high = 0;

    // now iterate over the amount of bits that are set in the LHS
    for (uint8_t x = uint128BitsAmount(lhs); x > 0; x--) {
        // left-shift the current result and remainder by 1
        div_result = uint128LeftShift(div_result, 1);
        remainder = uint128LeftShift(remainder, 1);

        // we get the value of the bit at position X, where position 0 is the least-significant bit
        if (uint128IsBitSet(lhs, x - 1)) {
            // increment the remainder
            addInPlace(remainder, 1);
        }
        if (greaterThanOrEquals(remainder, rhs)) {
            // the remainder has passed the division multiplier: add one to the divide result
            remainder = Sub(remainder, rhs);
            addInPlace(div_result, 1);
        }
    }
    return div_result;
}

uint128_t UInt128_t::Div(uint128_t lhs, uint128_t rhs) {
    if (rhs.high == 0 && rhs.low == 0) {
        throw common::RuntimeException("Divide by zero.");
    }
    uint128_t remainder{};
    return divMod(lhs, rhs, remainder);
}

uint128_t UInt128_t::Mod(uint128_t lhs, uint128_t rhs) {
    if (rhs.high == 0 && rhs.low == 0) {
        throw common::RuntimeException("Modulo by zero.");
    }
    uint128_t result{};
    divMod(lhs, rhs, result);
    return result;
}

uint128_t UInt128_t::Xor(uint128_t lhs, uint128_t rhs) {
    uint128_t result{lhs.low ^ rhs.low, lhs.high ^ rhs.high};
    return result;
}

uint128_t UInt128_t::BinaryAnd(uint128_t lhs, uint128_t rhs) {
    uint128_t result{lhs.low & rhs.low, lhs.high & rhs.high};
    return result;
}

uint128_t UInt128_t::BinaryOr(uint128_t lhs, uint128_t rhs) {
    uint128_t result{lhs.low | rhs.low, lhs.high | rhs.high};
    return result;
}

uint128_t UInt128_t::BinaryNot(uint128_t val) {
    return uint128_t{~val.low, ~val.high};
}

uint128_t UInt128_t::LeftShift(uint128_t lhs, int amount) {
    return amount >= 64 ?
               uint128_t(0, lhs.low << (amount - 64)) :
           amount == 0 ?
               lhs :
               uint128_t{lhs.low << amount, (lhs.high << amount) | (lhs.low >> (64 - amount))};
}

uint128_t UInt128_t::RightShift(uint128_t lhs, int amount) {
    return amount >= 64 ?
               uint128_t(lhs.high >> (amount - 64), 0) :
           amount == 0 ?
               lhs :
               uint128_t((lhs.low >> amount) | (lhs.high << (64 - amount)), lhs.high >> amount);
}

//===============================================================================================
// Cast operation
//===============================================================================================
template<class DST, bool SIGNED = true>
bool TryCastUint128Template(uint128_t input, DST& result) {
    if (input.high == 0 && input.low <= uint64_t(function::NumericLimits<DST>::maximum())) {
        result = static_cast<DST>(input.low);
        return true;
    }
    return false;
}
// we can use the above template if we can get max using something like DST.max

template<>
bool UInt128_t::tryCast(uint128_t input, int8_t& result) {
    return TryCastUint128Template<int8_t>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, int16_t& result) {
    return TryCastUint128Template<int16_t>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, int32_t& result) {
    return TryCastUint128Template<int32_t>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, int64_t& result) {
    return TryCastUint128Template<int64_t>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, uint8_t& result) {
    return TryCastUint128Template<uint8_t, false>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, uint16_t& result) {
    return TryCastUint128Template<uint16_t, false>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, uint32_t& result) {
    return TryCastUint128Template<uint32_t, false>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, uint64_t& result) {
    return TryCastUint128Template<uint64_t, false>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, int128_t& result) { // unsigned to signed
    if (input.high > (uint64_t)(function::NumericLimits<int64_t>::maximum())) {
        return false;
    }
    result = {input.low, int64_t(input.high)};
    return true;
}

template<>
bool UInt128_t::tryCast(uint128_t input, float& result) {
    double temp_res = NAN;
    tryCast(input, temp_res);
    result = static_cast<float>(temp_res);
    return true;
}

template<class REAL_T>
bool CastUint128ToFloating(uint128_t input, REAL_T& result) {
    result = REAL_T(input.high) * REAL_T(function::NumericLimits<uint64_t>::maximum()) +
             REAL_T(input.low);
    return true;
}

template<>
bool UInt128_t::tryCast(uint128_t input, double& result) {
    return CastUint128ToFloating<double>(input, result);
}

template<>
bool UInt128_t::tryCast(uint128_t input, long double& result) {
    return CastUint128ToFloating<long double>(input, result);
}

template<class SRC>
uint128_t tryCastToTemplate(SRC value) {
    if (value < 0) {
        throw common::OverflowException("Cannot cast negative value to UINT128.");
    }
    uint128_t result{};
    result.low = (uint64_t)value;
    result.high = 0;
    return result;
}

template<>
bool UInt128_t::tryCastTo(int8_t value, uint128_t& result) {
    result = tryCastToTemplate<int8_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(int16_t value, uint128_t& result) {
    result = tryCastToTemplate<int16_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(int32_t value, uint128_t& result) {
    result = tryCastToTemplate<int32_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(int64_t value, uint128_t& result) {
    result = tryCastToTemplate<int64_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(uint8_t value, uint128_t& result) {
    result = tryCastToTemplate<uint8_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(uint16_t value, uint128_t& result) {
    result = tryCastToTemplate<uint16_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(uint32_t value, uint128_t& result) {
    result = tryCastToTemplate<uint32_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(uint64_t value, uint128_t& result) {
    result = tryCastToTemplate<uint64_t>(value);
    return true;
}

template<>
bool UInt128_t::tryCastTo(uint128_t value, uint128_t& result) {
    result = value;
    return true;
}

template<>
bool UInt128_t::tryCastTo(float value, uint128_t& result) {
    return tryCastTo(double(value), result);
}

template<class REAL_T>
bool castFloatingToUint128(REAL_T value, uint128_t& result) {
    if (value < 0.0 || value >= 340282366920938463463374607431768211455.0) {
        return false;
    }
    value = std::nearbyint(value);
    result.low = (uint64_t)fmod(value, REAL_T(function::NumericLimits<uint64_t>::maximum()));
    result.high = (uint64_t)(value / REAL_T(function::NumericLimits<uint64_t>::maximum()));
    return true;
}

template<>
bool UInt128_t::tryCastTo(double value, uint128_t& result) {
    return castFloatingToUint128<double>(value, result);
}

template<>
bool UInt128_t::tryCastTo(long double value, uint128_t& result) {
    return castFloatingToUint128<long double>(value, result);
}
//===============================================================================================

template<NumericTypes T>
void constructUInt128Template(T value, uint128_t& result) {
    uint128_t casted = UInt128_t::castTo(value);
    result.low = casted.low;
    result.high = casted.high;
}

uint128_t::uint128_t(int64_t value) {
    auto result = UInt128_t::castTo(value);
    this->low = result.low;
    this->high = result.high;
}

uint128_t::uint128_t(int32_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(int16_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(int8_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(uint64_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(uint32_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(uint16_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(uint8_t value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(double value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

uint128_t::uint128_t(float value) { // NOLINT: fields are constructed by the template
    constructUInt128Template(value, *this);
}

//============================================================================================
bool operator==(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::equals(lhs, rhs);
}

bool operator!=(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::notEquals(lhs, rhs);
}

bool operator>(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::greaterThan(lhs, rhs);
}

bool operator>=(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::greaterThanOrEquals(lhs, rhs);
}

bool operator<(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::lessThan(lhs, rhs);
}

bool operator<=(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::lessThanOrEquals(lhs, rhs);
}

uint128_t uint128_t::operator-() const {
    return UInt128_t::negate(*this);
}

// support for operations like (int32_t)x + (uint128_t)y

uint128_t operator+(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::Add(lhs, rhs);
}
uint128_t operator-(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::Sub(lhs, rhs);
}
uint128_t operator*(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::Mul(lhs, rhs);
}
uint128_t operator/(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::Div(lhs, rhs);
}
uint128_t operator%(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::Mod(lhs, rhs);
}

uint128_t operator^(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::Xor(lhs, rhs);
}

uint128_t operator&(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::BinaryAnd(lhs, rhs);
}

uint128_t operator|(const uint128_t& lhs, const uint128_t& rhs) {
    return UInt128_t::BinaryOr(lhs, rhs);
}

uint128_t operator~(const uint128_t& val) {
    return UInt128_t::BinaryNot(val);
}

uint128_t operator<<(const uint128_t& lhs, int amount) {
    return UInt128_t::LeftShift(lhs, amount);
}

uint128_t operator>>(const uint128_t& lhs, int amount) {
    return UInt128_t::RightShift(lhs, amount);
}

// inplace arithmetic operators
uint128_t& uint128_t::operator+=(const uint128_t& rhs) {
    if (!UInt128_t::addInPlace(*this, rhs)) {
        throw common::OverflowException("UINT128 is out of range: cannot add in place.");
    }
    return *this;
}

uint128_t& uint128_t::operator*=(const uint128_t& rhs) {
    *this = UInt128_t::Mul(*this, rhs);
    return *this;
}

uint128_t& uint128_t::operator|=(const uint128_t& rhs) {
    *this = UInt128_t::BinaryOr(*this, rhs);
    return *this;
}

uint128_t& uint128_t::operator&=(const uint128_t& rhs) {
    *this = UInt128_t::BinaryAnd(*this, rhs);
    return *this;
}

template<class T>
static T NarrowCast(const uint128_t& input) {
    return static_cast<T>(input.low);
}

uint128_t::operator int64_t() const {
    return NarrowCast<int64_t>(*this);
}

uint128_t::operator int32_t() const {
    return NarrowCast<int32_t>(*this);
}

uint128_t::operator int16_t() const {
    return NarrowCast<int16_t>(*this);
}

uint128_t::operator int8_t() const {
    return NarrowCast<int8_t>(*this);
}

uint128_t::operator uint64_t() const {
    return NarrowCast<uint64_t>(*this);
}

uint128_t::operator uint32_t() const {
    return NarrowCast<uint32_t>(*this);
}

uint128_t::operator uint16_t() const {
    return NarrowCast<uint16_t>(*this);
}

uint128_t::operator uint8_t() const {
    return NarrowCast<uint8_t>(*this);
}

uint128_t::operator double() const {
    double result = NAN;
    [[maybe_unused]] bool success =
        UInt128_t::tryCast(*this, result); // casting to double should always succeed
    KU_ASSERT(success);
    return result;
}

uint128_t::operator float() const {
    float result = NAN;
    [[maybe_unused]] bool success = UInt128_t::tryCast(*this,
        result); // casting overly large values to float currently returns inf
    KU_ASSERT(success);
    return result;
}

uint128_t::operator int128_t() const {
    int128_t result{};
    if (!UInt128_t::tryCast(*this, result)) {
        throw common::OverflowException(common::stringFormat("Value {} is not within INT128 range.",
            common::TypeUtils::toString(*this)));
    }
    return result;
}

} // namespace lbug::common

std::size_t std::hash<lbug::common::uint128_t>::operator()(
    const lbug::common::uint128_t& v) const noexcept {
    lbug::common::hash_t hash = 0;
    lbug::function::Hash::operation(v, hash);
    return hash;
}
