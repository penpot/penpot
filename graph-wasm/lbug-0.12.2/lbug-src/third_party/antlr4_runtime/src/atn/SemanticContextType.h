/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <cstddef>

#include "antlr4-common.h"

namespace antlr4 {
namespace atn {

  enum class SemanticContextType : size_t {
    PREDICATE = 1,
    PRECEDENCE = 2,
    AND = 3,
    OR = 4,
  };

} // namespace atn
} // namespace antlr4
