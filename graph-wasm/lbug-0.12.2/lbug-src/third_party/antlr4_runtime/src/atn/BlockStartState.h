/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/DecisionState.h"

namespace antlr4 {
namespace atn {

  ///  The start of a regular {@code (...)} block.
  class ANTLR4CPP_PUBLIC BlockStartState : public DecisionState {
  public:
    static bool is(const ATNState &atnState) {
      const auto stateType = atnState.getStateType();
      return stateType >= ATNStateType::BLOCK_START && stateType <= ATNStateType::STAR_BLOCK_START;
    }

    static bool is(const ATNState *atnState) { return atnState != nullptr && is(*atnState); }

    BlockEndState *endState = nullptr;

  protected:
    using DecisionState::DecisionState;
  };

} // namespace atn
} // namespace antlr4
