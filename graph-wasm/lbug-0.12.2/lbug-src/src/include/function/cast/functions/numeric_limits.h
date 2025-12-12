#pragma once

#include <array>
#include <cstdint>
#include <limits>

#include "common/types/int128_t.h"
#include "common/types/uint128_t.h"

namespace lbug {
namespace function {

template<class T>
struct NumericLimits {
    static constexpr T minimum() { return std::numeric_limits<T>::lowest(); }
    static constexpr T maximum() { return std::numeric_limits<T>::max(); }
    static constexpr bool isSigned() { return std::is_signed<T>::value; }
    template<typename V>
    static bool isInBounds(V val) {
        return minimum() <= val && val <= maximum();
    }
    static constexpr uint64_t maxNumDigits();
};

template<>
struct NumericLimits<common::int128_t> {
    static constexpr common::int128_t minimum() {
        return {0, std::numeric_limits<int64_t>::lowest()};
    }
    static constexpr common::int128_t maximum() {
        return {std::numeric_limits<uint64_t>::max(), std::numeric_limits<int64_t>::max()};
    }
    template<typename V>
    static bool isInBounds(V val) {
        return minimum() <= val && val <= maximum();
    }
    static constexpr bool isSigned() { return true; }
    static constexpr uint64_t maxNumDigits() { return 39; }
};

template<>
struct NumericLimits<common::uint128_t> {
    static constexpr common::uint128_t minimum() { return {0, 0}; }
    static constexpr common::uint128_t maximum() {
        return {std::numeric_limits<uint64_t>::max(), std::numeric_limits<uint64_t>::max()};
    }
    template<typename V>
    static bool isInBounds(V val) {
        return minimum() <= val && val <= maximum();
    }
    static constexpr bool isSigned() { return false; }
    static constexpr uint64_t maxNumDigits() { return 39; }
};

template<>
constexpr uint64_t NumericLimits<int8_t>::maxNumDigits() {
    return 3;
}

template<>
constexpr uint64_t NumericLimits<int16_t>::maxNumDigits() {
    return 5;
}

template<>
constexpr uint64_t NumericLimits<int32_t>::maxNumDigits() {
    return 10;
}

template<>
constexpr uint64_t NumericLimits<int64_t>::maxNumDigits() {
    return 19;
}

template<>
constexpr uint64_t NumericLimits<uint8_t>::maxNumDigits() {
    return 3;
}

template<>
constexpr uint64_t NumericLimits<uint16_t>::maxNumDigits() {
    return 5;
}

template<>
constexpr uint64_t NumericLimits<uint32_t>::maxNumDigits() {
    return 10;
}

template<>
constexpr uint64_t NumericLimits<uint64_t>::maxNumDigits() {
    return 20;
}

template<>
constexpr uint64_t NumericLimits<float>::maxNumDigits() {
    return 127;
}

template<>
constexpr uint64_t NumericLimits<double>::maxNumDigits() {
    return 250;
}

template<typename T>
static constexpr std::array<T, NumericLimits<T>::maxNumDigits()> pow10Sequence() {
    std::array<T, NumericLimits<T>::maxNumDigits()> retval{};
    retval[0] = 1;
    for (auto i = 1u; i < NumericLimits<T>::maxNumDigits(); i++) {
        retval[i] = retval[i - 1] * 10;
    }
    return retval;
}

template<>
constexpr std::array<common::int128_t, NumericLimits<common::int128_t>::maxNumDigits()>
pow10Sequence() {
    return {
        common::int128_t(1UL, 0LL),
        common::int128_t(10UL, 0LL),
        common::int128_t(100UL, 0LL),
        common::int128_t(1000UL, 0LL),
        common::int128_t(10000UL, 0LL),
        common::int128_t(100000UL, 0LL),
        common::int128_t(1000000UL, 0LL),
        common::int128_t(10000000UL, 0LL),
        common::int128_t(100000000UL, 0LL),
        common::int128_t(1000000000UL, 0LL),
        common::int128_t(10000000000UL, 0LL),
        common::int128_t(100000000000UL, 0LL),
        common::int128_t(1000000000000UL, 0LL),
        common::int128_t(10000000000000UL, 0LL),
        common::int128_t(100000000000000UL, 0LL),
        common::int128_t(1000000000000000UL, 0LL),
        common::int128_t(10000000000000000UL, 0LL),
        common::int128_t(100000000000000000UL, 0LL),
        common::int128_t(1000000000000000000UL, 0LL),
        common::int128_t(10000000000000000000UL, 0LL),
        common::int128_t(7766279631452241920UL, 5LL),
        common::int128_t(3875820019684212736UL, 54LL),
        common::int128_t(1864712049423024128UL, 542LL),
        common::int128_t(200376420520689664UL, 5421LL),
        common::int128_t(2003764205206896640UL, 54210LL),
        common::int128_t(1590897978359414784UL, 542101LL),
        common::int128_t(15908979783594147840UL, 5421010LL),
        common::int128_t(11515845246265065472UL, 54210108LL),
        common::int128_t(4477988020393345024UL, 542101086LL),
        common::int128_t(7886392056514347008UL, 5421010862LL),
        common::int128_t(5076944270305263616UL, 54210108624LL),
        common::int128_t(13875954555633532928UL, 542101086242LL),
        common::int128_t(9632337040368467968UL, 5421010862427LL),
        common::int128_t(4089650035136921600UL, 54210108624275LL),
        common::int128_t(4003012203950112768UL, 542101086242752LL),
        common::int128_t(3136633892082024448UL, 5421010862427522LL),
        common::int128_t(12919594847110692864UL, 54210108624275221LL),
        common::int128_t(68739955140067328UL, 542101086242752217LL),
        common::int128_t(687399551400673280UL, 5421010862427522170LL),
    }; // Couldn't find a clean way to do this
}

template<>
constexpr std::array<common::uint128_t, NumericLimits<common::uint128_t>::maxNumDigits()>
pow10Sequence() {
    return {
        common::uint128_t(1UL, 0UL),
        common::uint128_t(10UL, 0UL),
        common::uint128_t(100UL, 0UL),
        common::uint128_t(1000UL, 0UL),
        common::uint128_t(10000UL, 0UL),
        common::uint128_t(100000UL, 0UL),
        common::uint128_t(1000000UL, 0UL),
        common::uint128_t(10000000UL, 0UL),
        common::uint128_t(100000000UL, 0UL),
        common::uint128_t(1000000000UL, 0UL),
        common::uint128_t(10000000000UL, 0UL),
        common::uint128_t(100000000000UL, 0UL),
        common::uint128_t(1000000000000UL, 0UL),
        common::uint128_t(10000000000000UL, 0UL),
        common::uint128_t(100000000000000UL, 0UL),
        common::uint128_t(1000000000000000UL, 0UL),
        common::uint128_t(10000000000000000UL, 0UL),
        common::uint128_t(100000000000000000UL, 0UL),
        common::uint128_t(1000000000000000000UL, 0UL),
        common::uint128_t(10000000000000000000UL, 0UL),
        common::uint128_t(7766279631452241920UL, 5UL),
        common::uint128_t(3875820019684212736UL, 54UL),
        common::uint128_t(1864712049423024128UL, 542UL),
        common::uint128_t(200376420520689664UL, 5421UL),
        common::uint128_t(2003764205206896640UL, 54210UL),
        common::uint128_t(1590897978359414784UL, 542101UL),
        common::uint128_t(15908979783594147840UL, 5421010UL),
        common::uint128_t(11515845246265065472UL, 54210108UL),
        common::uint128_t(4477988020393345024UL, 542101086UL),
        common::uint128_t(7886392056514347008UL, 5421010862UL),
        common::uint128_t(5076944270305263616UL, 54210108624UL),
        common::uint128_t(13875954555633532928UL, 542101086242UL),
        common::uint128_t(9632337040368467968UL, 5421010862427UL),
        common::uint128_t(4089650035136921600UL, 54210108624275UL),
        common::uint128_t(4003012203950112768UL, 542101086242752UL),
        common::uint128_t(3136633892082024448UL, 5421010862427522UL),
        common::uint128_t(12919594847110692864UL, 54210108624275221UL),
        common::uint128_t(68739955140067328UL, 542101086242752217UL),
        common::uint128_t(687399551400673280UL, 5421010862427522170UL),
    };
}

} // namespace function
} // namespace lbug
