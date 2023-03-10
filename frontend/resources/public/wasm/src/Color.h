#pragma once

template <typename T>
struct Color {
  T r, g, b, a;

  Color<T>& set(T new_r, T new_g, T new_b, T new_a)
  {
    r = new_r;
    g = new_g;
    b = new_b;
    a = new_a;
    return *this;
  }

  Color<T>& set(T new_r, T new_g, T new_b)
  {
    r = new_r;
    g = new_g;
    b = new_b;
    return *this;
  }

  Color<T> operator+(const Color<T> other)
  {
    return {
      r + other.r,
      g + other.g,
      b + other.b
    };
  }

  Color<T> operator-(const Color<T> other)
  {
    return {
      r - other.r,
      g - other.g,
      b - other.b
    };
  }

  Color<T> operator*(const Color<T> other)
  {
    return {
      r * other.r,
      g * other.g,
      b * other.b
    };
  }

  Color<T> operator/(const Color<T> other)
  {
    return {
      r / other.r,
      g / other.g,
      b / other.b
    };
  }


  Color<T> operator+(const T scalar)
  {
    return {
      r + scalar,
      g + scalar,
      b + scalar
    };
  }

  Color<T> operator-(const T scalar)
  {
    return {
      r - scalar,
      g - scalar,
      b - scalar
    };
  }

  Color<T> operator*(const T scalar)
  {
    return {
      r * scalar,
      g * scalar,
      b * scalar
    };
  }

  Color<T> operator/(const T scalar)
  {
    return {
      r / scalar,
      g / scalar,
      b / scalar
    };
  }
}
