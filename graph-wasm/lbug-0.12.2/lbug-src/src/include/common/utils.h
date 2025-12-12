#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>

#include "common/assert.h"
#include "common/numeric_utils.h"
#include "common/types/int128_t.h"
#include <bit>

namespace lbug {
namespace common {

class BitmaskUtils {
public:
    template<typename T>
        requires std::integral<T>
    static T all1sMaskForLeastSignificantBits(uint32_t numBits) {
        KU_ASSERT(numBits <= 64);
        using U = numeric_utils::MakeUnSignedT<T>;
        return (T)(numBits == (sizeof(U) * 8) ? std::numeric_limits<U>::max() :
                                                static_cast<U>(((U)1 << numBits) - 1));
    }

    // constructs all 1s mask while avoiding overflow/underflow for int128
    template<typename T>
        requires std::same_as<std::remove_cvref_t<T>, int128_t>
    static T all1sMaskForLeastSignificantBits(uint32_t numBits) {
        static constexpr uint8_t numBitsInT = sizeof(T) * 8;

        // use ~T(1) instead of ~T(0) to avoid sign-bit filling
        const T fullMask = ~(T(1) << (numBitsInT - 1));

        const size_t numBitsToDiscard = (numBitsInT - 1 - numBits);
        return (fullMask >> numBitsToDiscard);
    }
};

uint64_t nextPowerOfTwo(uint64_t v);
uint64_t prevPowerOfTwo(uint64_t v);

bool isLittleEndian();

template<numeric_utils::IsIntegral T>
constexpr T ceilDiv(T a, T b) {
    return (a / b) + (a % b != 0);
}

template<std::integral To, std::integral From>
constexpr To safeIntegerConversion(From val) {
    KU_ASSERT(static_cast<To>(val) == val);
    return val;
}

template<typename T, typename Container>
bool containsValue(const Container& container, const T& value) {
    return std::find(container.begin(), container.end(), value) != container.end();
}

template<std::integral T>
constexpr T countBits(T) {
    constexpr T bitsPerByte = 8;
    return sizeof(T) * bitsPerByte;
}

template<numeric_utils::IsIntegral T>
struct CountZeros {
    static constexpr idx_t Leading(T value_in) { return std::countl_zero(value_in); }
    static constexpr idx_t Trailing(T value_in) { return std::countr_zero(value_in); }
};

template<>
struct CountZeros<int128_t> {
    static constexpr idx_t Leading(int128_t value) {
        const uint64_t upper = static_cast<uint64_t>(value.high);
        const uint64_t lower = value.low;

        if (upper) {
            return CountZeros<uint64_t>::Leading(upper);
        }
        if (lower) {
            return 64 + CountZeros<uint64_t>::Leading(lower);
        }
        return 128;
    }

    static constexpr idx_t Trailing(int128_t value) {
        const uint64_t upper = static_cast<uint64_t>(value.high);
        const uint64_t lower = value.low;

        if (lower) {
            return CountZeros<uint64_t>::Trailing(lower);
        }
        if (upper) {
            return 64 + CountZeros<uint64_t>::Trailing(upper);
        }
        return 128;
    }
};

} // namespace common
} // namespace lbug
