/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <cstddef>
#include <string>

#include "antlr4-common.h"

namespace antlr4 {
namespace atn {

  // Constants for ATNState serialization.
  enum class ATNStateType : size_t {
    INVALID = 0,
    BASIC = 1,
    RULE_START = 2,
    BLOCK_START = 3,
    PLUS_BLOCK_START = 4,
    STAR_BLOCK_START = 5,
    TOKEN_START = 6,
    RULE_STOP = 7,
    BLOCK_END = 8,
    STAR_LOOP_BACK = 9,
    STAR_LOOP_ENTRY = 10,
    PLUS_LOOP_BACK = 11,
    LOOP_END = 12,
  };

  ANTLR4CPP_PUBLIC std::string atnStateTypeName(ATNStateType atnStateType);

} // namespace atn
} // namespace antlr4
