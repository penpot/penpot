/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/ATNState.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC BasicState final : public ATNState {
  public:
    static bool is(const ATNState &atnState) { return atnState.getStateType() == ATNStateType::BASIC; }

    static bool is(const ATNState *atnState) { return atnState != nullptr && is(*atnState); }

    BasicState() : ATNState(ATNStateType::BASIC) {}
  };

} // namespace atn
} // namespace antlr4
