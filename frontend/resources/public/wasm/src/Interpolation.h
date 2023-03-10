#pragma once

namespace Interpolation {

  template <typename T>
  T linear(const T x, const T a, const T b) {
    return (1 - x) * a + x * b;
  }

  template <typename T>
  T quadratic(const T x, const T a, const T b, const T c) {
    return linear(x, linear(x, a, b), linear(x, b, c));
  }

  template <typename T>
  T cubic(const T x, const T a, const T b, const T c, const T d) {
    return linear(x, quadratic(x, a, b, c), quadratic(x, b, c, d));
  }

}
