#pragma once

#include <cmath>
#include <iostream>

#include "Interpolation.h"

template <typename T>
struct Vector2 {
  T x, y;

  Vector2() : x(0), y(0) {}
  Vector2(T x, T y) : x(x), y(y) {}
  Vector2(const Vector2<T>& other) : x(other.x), y(other.y) {}

  auto dot(const Vector2<T> &other) const
  {
    return x * other.x + y * other.y;
  }

  auto cross(const Vector2<T> &other) const
  {
    return x * other.y - y * other.x;
  }

  auto direction() const
  {
    return std::atan2(y, x);
  }

  auto length() const
  {
    return std::hypot(x, y);
  }

  auto lengthSquared() const
  {
    return x * x + y * y;
  }

  Vector2<T>& normalize()
  {
    auto l = length();
    return set(x / l, x / l);
  }

  Vector2<T>& perpLeft()
  {
    return set(y, -x);
  }

  Vector2<T>& perpRight()
  {
    return set(-y, x);
  }

  Vector2<T>& rotate(auto rotation)
  {
    auto c = std::cos(rotation);
    auto s = std::sin(rotation);
    return set(
      c * x - s * y,
      s * x + c * y
    );
  }

  Vector2<T>& scale(auto s)
  {
    return set(
      x * s,
      y * s
    );
  }

  Vector2<T>& set(T newX, T newY)
  {
    x = newX;
    y = newY;
    return *this;
  }

  Vector2<T>& copy(const Vector2<T>& other)
  {
    return set(other.x, other.y);
  }

  Vector2<T>& linear(auto p, Vector2<T>& a, Vector2<T>& b)
  {
    return set(
      Interpolation::linear(p, a.x, b.x),
      Interpolation::linear(p, a.y, b.y)
    );
  }

  Vector2<T>& quadratic(auto p, Vector2<T>& a, Vector2<T>& b, Vector2<T>& c)
  {
    return set(
      Interpolation::quadratic(p, a.x, b.x, c.x),
      Interpolation::quadratic(p, a.y, b.y, c.y)
    );
  }

  Vector2<T>& cubic(auto p, Vector2<T>& a, Vector2<T>& b, Vector2<T>& c, Vector2<T>& d)
  {
    return set(
      Interpolation::cubic(p, a.x, b.x, c.x, d.x),
      Interpolation::cubic(p, a.y, b.y, c.y, d.y)
    );
  }

  Vector2<T> operator*(const Matrix23<T> m)
  {
    return {
      x * m.a + y * m.c + m.tx,
      x * m.b + y * m.d + m.ty
    };
  }

  Vector2<T> operator+(const Vector2<T> other)
  {
    return {
      x + other.x,
      y + other.y
    };
  }

  Vector2<T> operator-(const Vector2<T> other)
  {
    return {
      x - other.x,
      y - other.y
    };
  }

  Vector2<T> operator*(const Vector2<T> other)
  {
    return {
      x * other.x,
      y * other.y
    };
  }

  Vector2<T> operator/(const Vector2<T> other)
  {
    return {
      x / other.x,
      y / other.y
    };
  }

  Vector2<T> operator+(T scalar)
  {
    return {
      x + scalar,
      y + scalar
    };
  }

  Vector2<T> operator-(T scalar)
  {
    return {
      x - scalar,
      y - scalar
    };
  }

  Vector2<T> operator*(T scalar)
  {
    return {
      x * scalar,
      y * scalar
    };
  }

  Vector2<T> operator/(T scalar)
  {
    return {
      x / scalar,
      y / scalar
    };
  }

  Vector2<T> operator-() const
  {
    return {
      -x,
      -y
    };
  }
};

template <typename T>
std::ostream& operator<<(std::ostream &os, const Vector2<T>& vector)
{
  os << "Vector2(" << vector.x << ", " << vector.y << ")";
  return os;
}
