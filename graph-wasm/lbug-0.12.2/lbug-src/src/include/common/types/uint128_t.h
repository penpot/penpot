#pragma once

#include <cstdint>
#include <string>

#include "common/api.h"
#include "common/exception/overflow.h"
#include "common/types/types.h"

namespace lbug {
namespace common {

struct int128_t;

struct LBUG_API uint128_t {
    uint64_t low;
    uint64_t high;

    uint128_t() noexcept = default;
    uint128_t(int64_t value);  // NOLINT: Allow implicit conversion from numeric values
    uint128_t(int32_t value);  // NOLINT: Allow implicit conversion from numeric values
    uint128_t(int16_t value);  // NOLINT: Allow implicit conversion from numeric values
    uint128_t(int8_t value);   // NOLINT: Allow implicit conversion from numeric values
    uint128_t(uint64_t value); // NOLINT: Allow implicit conversion from numeric values
    uint128_t(uint32_t value); // NOLINT: Allow implicit conversion from numeric values
    uint128_t(uint16_t value); // NOLINT: Allow implicit conversion from numeric values
    uint128_t(uint8_t value);  // NOLINT: Allow implicit conversion from numeric values
    uint128_t(double value);   // NOLINT: Allow implicit conversion from numeric values
    uint128_t(float value);    // NOLINT: Allow implicit conversion from numeric values

    constexpr uint128_t(uint64_t low, uint64_t high) noexcept : low(low), high(high) {}

    constexpr uint128_t(const uint128_t&) noexcept = default;
    constexpr uint128_t(uint128_t&&) noexcept = default;
    uint128_t& operator=(const uint128_t&) noexcept = default;
    uint128_t& operator=(uint128_t&&) noexcept = default;

    uint128_t operator-() const;

    // inplace arithmetic operators
    uint128_t& operator+=(const uint128_t& rhs);
    uint128_t& operator*=(const uint128_t& rhs);
    uint128_t& operator|=(const uint128_t& rhs);
    uint128_t& operator&=(const uint128_t& rhs);

    // cast operators
    explicit operator int64_t() const;
    explicit operator int32_t() const;
    explicit operator int16_t() const;
    explicit operator int8_t() const;
    explicit operator uint64_t() const;
    explicit operator uint32_t() const;
    explicit operator uint16_t() const;
    explicit operator uint8_t() const;
    explicit operator double() const;
    explicit operator float() const;

    operator int128_t() const; // NOLINT: Allow implicit conversion from uint128 to int128
};

// arithmetic operators
LBUG_API uint128_t operator+(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator-(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator*(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator/(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator%(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator^(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator&(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator~(const uint128_t& val);
LBUG_API uint128_t operator|(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API uint128_t operator<<(const uint128_t& lhs, int amount);
LBUG_API uint128_t operator>>(const uint128_t& lhs, int amount);

// comparison operators
LBUG_API bool operator==(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API bool operator!=(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API bool operator>(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API bool operator>=(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API bool operator<(const uint128_t& lhs, const uint128_t& rhs);
LBUG_API bool operator<=(const uint128_t& lhs, const uint128_t& rhs);

class UInt128_t {
public:
    static std::string toString(uint128_t input);

    template<class T>
    static bool tryCast(uint128_t input, T& result);

    template<class T>
    static T cast(uint128_t input) {
        T result;
        tryCast(input, result);
        return result;
    }

    template<class T>
    static bool tryCastTo(T value, uint128_t& result);

    template<class T>
    static uint128_t castTo(T value) {
        uint128_t result{};
        if (!tryCastTo(value, result)) {
            throw common::OverflowException("UINT128 is out of range");
        }
        return result;
    }

    // negate (required by function/arithmetic/negate.h)
    static void negateInPlace(uint128_t& input) {
        input.low = UINT64_MAX + 1 - input.low;
        input.high = -input.high - 1 + (input.low == 0);
    }

    static uint128_t negate(uint128_t input) {
        negateInPlace(input);
        return input;
    }

    static bool tryMultiply(uint128_t lhs, uint128_t rhs, uint128_t& result);

    static uint128_t Add(uint128_t lhs, uint128_t rhs);
    static uint128_t Sub(uint128_t lhs, uint128_t rhs);
    static uint128_t Mul(uint128_t lhs, uint128_t rhs);
    static uint128_t Div(uint128_t lhs, uint128_t rhs);
    static uint128_t Mod(uint128_t lhs, uint128_t rhs);
    static uint128_t Xor(uint128_t lhs, uint128_t rhs);
    static uint128_t LeftShift(uint128_t lhs, int amount);
    static uint128_t RightShift(uint128_t lhs, int amount);
    static uint128_t BinaryAnd(uint128_t lhs, uint128_t rhs);
    static uint128_t BinaryOr(uint128_t lhs, uint128_t rhs);
    static uint128_t BinaryNot(uint128_t val);

    static uint128_t divMod(uint128_t lhs, uint128_t rhs, uint128_t& remainder);
    static uint128_t divModPositive(uint128_t lhs, uint64_t rhs, uint64_t& remainder);

    static bool addInPlace(uint128_t& lhs, uint128_t rhs);
    static bool subInPlace(uint128_t& lhs, uint128_t rhs);

    // comparison operators
    static bool equals(uint128_t lhs, uint128_t rhs) {
        return lhs.low == rhs.low && lhs.high == rhs.high;
    }

    static bool notEquals(uint128_t lhs, uint128_t rhs) {
        return lhs.low != rhs.low || lhs.high != rhs.high;
    }

    static bool greaterThan(uint128_t lhs, uint128_t rhs) {
        return (lhs.high > rhs.high) || (lhs.high == rhs.high && lhs.low > rhs.low);
    }

    static bool greaterThanOrEquals(uint128_t lhs, uint128_t rhs) {
        return (lhs.high > rhs.high) || (lhs.high == rhs.high && lhs.low >= rhs.low);
    }

    static bool lessThan(uint128_t lhs, uint128_t rhs) {
        return (lhs.high < rhs.high) || (lhs.high == rhs.high && lhs.low < rhs.low);
    }

    static bool lessThanOrEquals(uint128_t lhs, uint128_t rhs) {
        return (lhs.high < rhs.high) || (lhs.high == rhs.high && lhs.low <= rhs.low);
    }
};

template<>
bool UInt128_t::tryCast(uint128_t input, int8_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, int16_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, int32_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, int64_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, uint8_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, uint16_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, uint32_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, uint64_t& result);
template<>
bool UInt128_t::tryCast(uint128_t input, int128_t& result); // unsigned to signed
template<>
bool UInt128_t::tryCast(uint128_t input, float& result);
template<>
bool UInt128_t::tryCast(uint128_t input, double& result);
template<>
bool UInt128_t::tryCast(uint128_t input, long double& result);

template<>
bool UInt128_t::tryCastTo(int8_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(int16_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(int32_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(int64_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(uint8_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(uint16_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(uint32_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(uint64_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(uint128_t value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(float value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(double value, uint128_t& result);
template<>
bool UInt128_t::tryCastTo(long double value, uint128_t& result);

} // namespace common
} // namespace lbug

template<>
struct std::hash<lbug::common::uint128_t> {
    std::size_t operator()(const lbug::common::uint128_t& v) const noexcept;
};
