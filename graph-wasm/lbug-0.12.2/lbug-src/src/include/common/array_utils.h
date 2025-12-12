#pragma once

#include <algorithm>
#include <array>
#include <cstddef>

namespace lbug::common {
template<typename T, size_t N1, size_t N2>
constexpr std::array<T, N1 + N2> arrayConcat(const std::array<T, N1>& arr1,
    const std::array<T, N2>& arr2) {
    std::array<T, N1 + N2> ret{};
    std::copy_n(arr1.cbegin(), arr1.size(), ret.begin());
    std::copy_n(arr2.cbegin(), arr2.size(), ret.begin() + arr1.size());
    return ret;
}
} // namespace lbug::common
