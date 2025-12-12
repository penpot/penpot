/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/ATNState.h"

namespace antlr4 {
namespace atn {

  /// Terminal node of a simple {@code (a|b|c)} block.
  class ANTLR4CPP_PUBLIC BlockEndState final : public ATNState {
  public:
    static bool is(const ATNState &atnState) { return atnState.getStateType() == ATNStateType::BLOCK_END; }

    static bool is(const ATNState *atnState) { return atnState != nullptr && is(*atnState); }

    BlockStartState *startState = nullptr;

    BlockEndState() : ATNState(ATNStateType::BLOCK_END) {}
  };

} // namespace atn
} // namespace antlr4
