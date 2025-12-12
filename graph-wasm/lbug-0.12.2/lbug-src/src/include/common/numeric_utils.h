#pragma once

#include <type_traits>

#include "common/types/int128_t.h"
#include "common/types/types.h"
#include <bit>
#include <concepts>

namespace lbug {
namespace common {
namespace numeric_utils {

template<typename T>
concept IsIntegral = std::integral<T> || std::same_as<std::remove_cvref_t<T>, int128_t>;

template<typename T>
concept IsSigned = std::same_as<T, int128_t> || std::numeric_limits<T>::is_signed;

template<typename T>
concept IsUnSigned = std::numeric_limits<T>::is_unsigned;

template<typename T>
struct MakeSigned {
    using type = std::make_signed_t<T>;
};

template<>
struct MakeSigned<int128_t> {
    using type = int128_t;
};

template<typename T>
using MakeSignedT = typename MakeSigned<T>::type;

template<typename T>
struct MakeUnSigned {
    using type = std::make_unsigned_t<T>;
};

template<>
struct MakeUnSigned<int128_t> {
    // currently evaluates to int128_t as we don't have an uint128_t type
    using type = int128_t;
};

template<typename T>
using MakeUnSignedT = typename MakeUnSigned<T>::type;

template<typename T>
decltype(auto) makeValueSigned(T value) {
    return static_cast<MakeSignedT<T>>(value);
}

template<typename T>
decltype(auto) makeValueUnSigned(T value) {
    return static_cast<MakeUnSignedT<T>>(value);
}

template<typename T>
constexpr int bitWidth(T x) {
    return std::bit_width(x);
}

template<>
constexpr int bitWidth<int128_t>(int128_t x) {
    if (x.high != 0) {
        constexpr size_t BITS_PER_BYTE = 8;
        return sizeof(x.low) * BITS_PER_BYTE + std::bit_width(makeValueUnSigned(x.high));
    }
    return std::bit_width(x.low);
}
} // namespace numeric_utils
} // namespace common
} // namespace lbug
