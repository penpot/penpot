/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/ATNState.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC DecisionState : public ATNState {
  public:
    static bool is(const ATNState &atnState) {
      const auto stateType = atnState.getStateType();
      return (stateType >= ATNStateType::BLOCK_START && stateType <= ATNStateType::TOKEN_START) ||
              stateType == ATNStateType::PLUS_LOOP_BACK ||
              stateType == ATNStateType::STAR_LOOP_ENTRY;
    }

    static bool is(const ATNState *atnState) { return atnState != nullptr && is(*atnState); }

    int decision = -1;
    bool nonGreedy = false;

    virtual std::string toString() const override;

  protected:
    using ATNState::ATNState;
  };

} // namespace atn
} // namespace antlr4
