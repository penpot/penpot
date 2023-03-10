#pragma once

#include "Vector2.h"

template <typename T>
struct Box2
{
  Vector2<T> position;
  Vector2<T> size;

  Box2(T x, T y, T width, T height) {
    position.set(x, y);
    size.set(width, height);
  }
  Box2(const Box2<T>& box) : position(box.position), size(box.size) {}
  Box2(const Vector2<T>& position, const Vector2<T>& size) : position(position), size(size) {}

  auto left() const
  {
    return position.x;
  }

  auto right() const
  {
    return position.x + size.x;
  }

  auto top() const
  {
    return position.y;
  }

  auto bottom() const
  {
    return position.y + size.y;
  }

  bool contains(const Vector2<T>& point) const {
    return point.x > position.x
        && point.x < position.x + size.x
        && point.y > position.y
        && point.y < position.y + size.y;
  }

  bool intersects(const Box2<T> &other) const {
    if (left() > other.right() || right() < other.left())
      return false;

    if (top() > other.bottom() || bottom() < other.top())
      return false;

    return true;
  }
};
