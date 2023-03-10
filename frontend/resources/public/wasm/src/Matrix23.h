#pragma once

#include <cmath>
#include <iostream>

template <typename T>
struct Matrix23 {
  // a c tx
  // b d ty
  T a, b, c, d, tx, ty;

  Matrix23() : a(1), b(0), c(0), d(1), tx(0), ty(0) {}
  Matrix23(T a, T b, T c, T d, T tx, T ty) : a(a), b(b), c(c), d(d), tx(tx), ty(ty) {}
  Matrix23(const Matrix23<T>& other) : a(other.a), b(other.b), c(other.c), d(other.d), tx(other.tx), ty(other.ty) {}

  auto determinant() const {
    return a * d - b * c;
  }

  Matrix23<T>& identity()
  {
    a = 1;
    b = 0;
    c = 0;
    d = 1;
    tx = 0;
    ty = 0;
    return *this;
  }

  Matrix23<T>& translate(T x, T y)
  {
    tx += x;
    ty += y;
    return *this;
  }

  Matrix23<T>& scale(T x, T y)
  {
    a *= x;
    b *= y;
    c *= x;
    d *= y;
    return *this;
  }

  Matrix23<T>& rotate(auto angle)
  {
    auto cos = std::cos(angle);
    auto sin = std::sin(angle);

    auto new_a = a * cos + c * sin;
    auto new_b = b * cos + d * sin;
    auto new_c = c * cos - a * sin;
    auto new_d = d * cos - b * sin;

    a = new_a;
    b = new_b;
    c = new_c;
    d = new_d;

    return *this;
  }

  Matrix23<T> invert()
  {
    auto det = determinant();
    if (det == 0)
    {
      return *this;
    }
    auto inv_det = 1.0 / det;
    return {
      d * inv_det,
      -b * inv_det,
      -c * inv_det,
      a * inv_det,
      (c * ty - d * tx) * inv_det,
      (b * tx - a * ty) * inv_det
    };
  }

  Matrix23<T> operator*(const Matrix23<T>& other)
  {
    //   M       N
    // a c x   a c x
    // b d y x b d y = T
    // 0 0 1   0 0 1
    return {
      a * other.a + b * other.c,
      a * other.b + b * other.d,
      c * other.a + d * other.c,
      c * other.b + d * other.d,
      tx * other.a + ty * other.c + other.tx,
      tx * other.b + ty * other.d + other.ty
    };
  }
};

template <typename T>
std::ostream &operator<<(std::ostream &os, const Matrix23<T> &matrix)
{
  os << "Matrix23(" << matrix.a << ", " << matrix.b << ", " << matrix.c << ", " << matrix.d << ", " << matrix.tx << ", " << matrix.ty << ")";
  return os;
}
