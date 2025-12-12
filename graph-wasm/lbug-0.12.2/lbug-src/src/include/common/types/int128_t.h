// =========================================================================================
// This int128 implementtaion got

// =========================================================================================
#pragma once

#include <cstdint>
#include <functional>
#include <string>

#include "common/api.h"
#include "common/exception/overflow.h"

namespace lbug {
namespace common {

struct LBUG_API int128_t;
struct uint128_t;

// System representation for int128_t.
struct LBUG_API int128_t {
    uint64_t low;
    int64_t high;

    int128_t() noexcept = default;
    int128_t(int64_t value);  // NOLINT: Allow implicit conversion from numeric values
    int128_t(int32_t value);  // NOLINT: Allow implicit conversion from numeric values
    int128_t(int16_t value);  // NOLINT: Allow implicit conversion from numeric values
    int128_t(int8_t value);   // NOLINT: Allow implicit conversion from numeric values
    int128_t(uint64_t value); // NOLINT: Allow implicit conversion from numeric values
    int128_t(uint32_t value); // NOLINT: Allow implicit conversion from numeric values
    int128_t(uint16_t value); // NOLINT: Allow implicit conversion from numeric values
    int128_t(uint8_t value);  // NOLINT: Allow implicit conversion from numeric values
    int128_t(double value);   // NOLINT: Allow implicit conversion from numeric values
    int128_t(float value);    // NOLINT: Allow implicit conversion from numeric values

    constexpr int128_t(uint64_t low, int64_t high) noexcept : low(low), high(high) {}

    constexpr int128_t(const int128_t&) noexcept = default;
    constexpr int128_t(int128_t&&) noexcept = default;
    int128_t& operator=(const int128_t&) noexcept = default;
    int128_t& operator=(int128_t&&) noexcept = default;

    int128_t operator-() const;

    // inplace arithmetic operators
    int128_t& operator+=(const int128_t& rhs);
    int128_t& operator*=(const int128_t& rhs);
    int128_t& operator|=(const int128_t& rhs);
    int128_t& operator&=(const int128_t& rhs);

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

    explicit operator uint128_t() const;
};

// arithmetic operators
LBUG_API int128_t operator+(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator-(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator*(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator/(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator%(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator^(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator&(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator~(const int128_t& val);
LBUG_API int128_t operator|(const int128_t& lhs, const int128_t& rhs);
LBUG_API int128_t operator<<(const int128_t& lhs, int amount);
LBUG_API int128_t operator>>(const int128_t& lhs, int amount);

// comparison operators
LBUG_API bool operator==(const int128_t& lhs, const int128_t& rhs);
LBUG_API bool operator!=(const int128_t& lhs, const int128_t& rhs);
LBUG_API bool operator>(const int128_t& lhs, const int128_t& rhs);
LBUG_API bool operator>=(const int128_t& lhs, const int128_t& rhs);
LBUG_API bool operator<(const int128_t& lhs, const int128_t& rhs);
LBUG_API bool operator<=(const int128_t& lhs, const int128_t& rhs);

class Int128_t {
public:
    static std::string toString(int128_t input);

    template<class T>
    static bool tryCast(int128_t input, T& result);

    template<class T>
    static T cast(int128_t input) {
        T result;
        tryCast(input, result);
        return result;
    }

    template<class T>
    static bool tryCastTo(T value, int128_t& result);

    template<class T>
    static int128_t castTo(T value) {
        int128_t result{};
        if (!tryCastTo(value, result)) {
            throw common::OverflowException("INT128 is out of range");
        }
        return result;
    }

    // negate
    static void negateInPlace(int128_t& input) {
        if (input.high == INT64_MIN && input.low == 0) {
            throw common::OverflowException("INT128 is out of range: cannot negate INT128_MIN");
        }
        input.low = UINT64_MAX + 1 - input.low;
        input.high = -input.high - 1 + (input.low == 0);
    }

    static int128_t negate(int128_t input) {
        negateInPlace(input);
        return input;
    }

    static bool tryMultiply(int128_t lhs, int128_t rhs, int128_t& result);

    static int128_t Add(int128_t lhs, int128_t rhs);
    static int128_t Sub(int128_t lhs, int128_t rhs);
    static int128_t Mul(int128_t lhs, int128_t rhs);
    static int128_t Div(int128_t lhs, int128_t rhs);
    static int128_t Mod(int128_t lhs, int128_t rhs);
    static int128_t Xor(int128_t lhs, int128_t rhs);
    static int128_t LeftShift(int128_t lhs, int amount);
    static int128_t RightShift(int128_t lhs, int amount);
    static int128_t BinaryAnd(int128_t lhs, int128_t rhs);
    static int128_t BinaryOr(int128_t lhs, int128_t rhs);
    static int128_t BinaryNot(int128_t val);

    static int128_t divMod(int128_t lhs, int128_t rhs, int128_t& remainder);
    static int128_t divModPositive(int128_t lhs, uint64_t rhs, uint64_t& remainder);

    static bool addInPlace(int128_t& lhs, int128_t rhs);
    static bool subInPlace(int128_t& lhs, int128_t rhs);

    // comparison operators
    static bool equals(int128_t lhs, int128_t rhs) {
        return lhs.low == rhs.low && lhs.high == rhs.high;
    }

    static bool notEquals(int128_t lhs, int128_t rhs) {
        return lhs.low != rhs.low || lhs.high != rhs.high;
    }

    static bool greaterThan(int128_t lhs, int128_t rhs) {
        return (lhs.high > rhs.high) || (lhs.high == rhs.high && lhs.low > rhs.low);
    }

    static bool greaterThanOrEquals(int128_t lhs, int128_t rhs) {
        return (lhs.high > rhs.high) || (lhs.high == rhs.high && lhs.low >= rhs.low);
    }

    static bool lessThan(int128_t lhs, int128_t rhs) {
        return (lhs.high < rhs.high) || (lhs.high == rhs.high && lhs.low < rhs.low);
    }

    static bool lessThanOrEquals(int128_t lhs, int128_t rhs) {
        return (lhs.high < rhs.high) || (lhs.high == rhs.high && lhs.low <= rhs.low);
    }
};

template<>
bool Int128_t::tryCast(int128_t input, int8_t& result);
template<>
bool Int128_t::tryCast(int128_t input, int16_t& result);
template<>
bool Int128_t::tryCast(int128_t input, int32_t& result);
template<>
bool Int128_t::tryCast(int128_t input, int64_t& result);
template<>
bool Int128_t::tryCast(int128_t input, uint8_t& result);
template<>
bool Int128_t::tryCast(int128_t input, uint16_t& result);
template<>
bool Int128_t::tryCast(int128_t input, uint32_t& result);
template<>
bool Int128_t::tryCast(int128_t input, uint64_t& result);
template<>
bool Int128_t::tryCast(int128_t input, uint128_t& result); // signed to unsigned
template<>
bool Int128_t::tryCast(int128_t input, float& result);
template<>
bool Int128_t::tryCast(int128_t input, double& result);
template<>
bool Int128_t::tryCast(int128_t input, long double& result);

template<>
bool Int128_t::tryCastTo(int8_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(int16_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(int32_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(int64_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(uint8_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(uint16_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(uint32_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(uint64_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(int128_t value, int128_t& result);
template<>
bool Int128_t::tryCastTo(float value, int128_t& result);
template<>
bool Int128_t::tryCastTo(double value, int128_t& result);
template<>
bool Int128_t::tryCastTo(long double value, int128_t& result);

} // namespace common
} // namespace lbug

template<>
struct std::hash<lbug::common::int128_t> {
    std::size_t operator()(const lbug::common::int128_t& v) const noexcept;
};
