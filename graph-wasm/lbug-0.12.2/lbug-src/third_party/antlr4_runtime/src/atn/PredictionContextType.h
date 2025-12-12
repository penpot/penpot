/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include <cstddef>

#include "antlr4-common.h"

namespace antlr4 {
namespace atn {

  enum class PredictionContextType : size_t {
    SINGLETON = 1,
    ARRAY = 2,
  };

} // namespace atn
} // namespace antlr4
