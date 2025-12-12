/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/ATNConfigSet.h"
#include "atn/ATNConfig.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC OrderedATNConfigSet final : public ATNConfigSet {
  public:
    OrderedATNConfigSet() = default;

  private:
    size_t hashCode(const ATNConfig &atnConfig) const override;

    bool equals(const ATNConfig &lhs, const ATNConfig &rhs) const override;
  };

} // namespace atn
} // namespace antlr4
