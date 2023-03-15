#pragma once

#include <cmath>
#include <iostream>

template <typename T>
struct Matrix2D {
  // a c tx
  // b d ty
  T a, b, c, d, tx, ty;

  Matrix2D() : a(1), b(0), c(0), d(1), tx(0), ty(0) {}
  Matrix2D(T a, T b, T c, T d, T tx, T ty) : a(a), b(b), c(c), d(d), tx(tx), ty(ty) {}
  Matrix2D(const Matrix2D<T>& other) : a(other.a), b(other.b), c(other.c), d(other.d), tx(other.tx), ty(other.ty) {}

  auto determinant() const {
    return a * d - b * c;
  }

  Matrix2D<T>& identity()
  {
    a = 1;
    b = 0;
    c = 0;
    d = 1;
    tx = 0;
    ty = 0;
    return *this;
  }

  Matrix2D<T>& translate(T x, T y)
  {
    tx += x;
    ty += y;
    return *this;
  }

  Matrix2D<T>& scale(T x, T y)
  {
    a *= x;
    b *= y;
    c *= x;
    d *= y;
    return *this;
  }

  Matrix2D<T>& rotate(auto angle)
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

  Matrix2D<T> invert()
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

  Matrix2D<T> operator*(const Matrix2D<T>& other)
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

  static Matrix2D<T> create_translation(const T x, const T y)
  {
    return { 1, 0, 0, 1, x, y };
  }

  static Matrix2D<T> create_scale(const T x, const T y)
  {
    return { x, 0, 0, y, 0, 0 };
  }

  static Matrix2D<T> create_rotation(auto angle)
  {
    auto c = std::cos(angle);
    auto s = std::sin(angle);
    return { c, s, -s, c, 0, 0 };
  }
};

template <typename T>
std::ostream &operator<<(std::ostream &os, const Matrix2D<T> &matrix)
{
  os << "Matrix2D(" << matrix.a << ", " << matrix.b << ", " << matrix.c << ", " << matrix.d << ", " << matrix.tx << ", " << matrix.ty << ")";
  return os;
}
