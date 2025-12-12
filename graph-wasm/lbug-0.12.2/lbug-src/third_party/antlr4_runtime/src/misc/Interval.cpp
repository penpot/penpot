/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/Interval.h"

using namespace antlr4::misc;

const Interval Interval::INVALID;

size_t Interval::hashCode() const {
  size_t hash = 23;
  hash = hash * 31 + static_cast<size_t>(a);
  hash = hash * 31 + static_cast<size_t>(b);
  return hash;
}

bool Interval::startsBeforeDisjoint(const Interval &other) const {
  return a < other.a && b < other.a;
}

bool Interval::startsBeforeNonDisjoint(const Interval &other) const {
  return a <= other.a && b >= other.a;
}

bool Interval::startsAfter(const Interval &other) const {
  return a > other.a;
}

bool Interval::startsAfterDisjoint(const Interval &other) const {
  return a > other.b;
}

bool Interval::startsAfterNonDisjoint(const Interval &other) const {
  return a > other.a && a <= other.b; // b >= other.b implied
}

bool Interval::disjoint(const Interval &other) const {
  return startsBeforeDisjoint(other) || startsAfterDisjoint(other);
}

bool Interval::adjacent(const Interval &other) const {
  return a == other.b + 1 || b == other.a - 1;
}

bool Interval::properlyContains(const Interval &other) const {
  return other.a >= a && other.b <= b;
}

Interval Interval::Union(const Interval &other) const {
  return Interval(std::min(a, other.a), std::max(b, other.b));
}

Interval Interval::intersection(const Interval &other) const {
  return Interval(std::max(a, other.a), std::min(b, other.b));
}

std::string Interval::toString() const {
  return std::to_string(a) + ".." + std::to_string(b);
}
