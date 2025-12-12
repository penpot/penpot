/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "antlr4-common.h"

namespace antlr4 {
namespace misc {

  // Helpers to convert certain unsigned symbols (e.g. Token::EOF) to their original numeric value (e.g. -1)
  // and vice versa. This is needed mostly for intervals to keep their original order and for toString()
  // methods to print the original numeric value (e.g. for tests).
  constexpr size_t numericToSymbol(ssize_t v) { return static_cast<size_t>(v); }
  constexpr ssize_t symbolToNumeric(size_t v) { return static_cast<ssize_t>(v); }

  /// An immutable inclusive interval a..b
  class ANTLR4CPP_PUBLIC Interval final {
  public:
    static const Interval INVALID;

    // Must stay signed to guarantee the correct sort order.
    ssize_t a;
    ssize_t b;

    constexpr Interval() : Interval(static_cast<ssize_t>(-1), static_cast<ssize_t>(-2)) {}

    constexpr explicit Interval(size_t a_, size_t b_) : Interval(symbolToNumeric(a_), symbolToNumeric(b_)) {}

    constexpr Interval(ssize_t a_, ssize_t b_) : a(a_), b(b_) {}

    /// return number of elements between a and b inclusively. x..x is length 1.
    ///  if b < a, then length is 0.  9..10 has length 2.
    constexpr size_t length() const { return b >= a ? static_cast<size_t>(b - a + 1) : 0; }

    constexpr bool operator==(const Interval &other) const { return a == other.a && b == other.b; }

    size_t hashCode() const;

    /// <summary>
    /// Does this start completely before other? Disjoint </summary>
    bool startsBeforeDisjoint(const Interval &other) const;

    /// <summary>
    /// Does this start at or before other? Nondisjoint </summary>
    bool startsBeforeNonDisjoint(const Interval &other) const;

    /// <summary>
    /// Does this.a start after other.b? May or may not be disjoint </summary>
    bool startsAfter(const Interval &other) const;

    /// <summary>
    /// Does this start completely after other? Disjoint </summary>
    bool startsAfterDisjoint(const Interval &other) const;

    /// <summary>
    /// Does this start after other? NonDisjoint </summary>
    bool startsAfterNonDisjoint(const Interval &other) const;

    /// <summary>
    /// Are both ranges disjoint? I.e., no overlap? </summary>
    bool disjoint(const Interval &other) const;

    /// <summary>
    /// Are two intervals adjacent such as 0..41 and 42..42? </summary>
    bool adjacent(const Interval &other) const;

    bool properlyContains(const Interval &other) const;

    /// <summary>
    /// Return the interval computed from combining this and other </summary>
    Interval Union(const Interval &other) const;

    /// <summary>
    /// Return the interval in common between this and o </summary>
    Interval intersection(const Interval &other) const;

    std::string toString() const;
  };

} // namespace atn
} // namespace antlr4
