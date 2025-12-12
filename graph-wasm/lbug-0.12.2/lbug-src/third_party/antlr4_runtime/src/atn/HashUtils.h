/* Copyright (c) 2022 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <cstddef>

namespace antlr4 {
namespace atn {

  inline bool cachedHashCodeEqual(size_t lhs, size_t rhs) {
    return lhs == rhs || lhs == 0 || rhs == 0;
  }

} // namespace atn
} // namespace antlr4
