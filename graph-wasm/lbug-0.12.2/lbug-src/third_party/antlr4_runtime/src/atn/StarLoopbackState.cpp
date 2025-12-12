/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/StarLoopEntryState.h"
#include "atn/Transition.h"
#include "support/Casts.h"

#include "atn/StarLoopbackState.h"

using namespace antlr4::atn;

StarLoopEntryState *StarLoopbackState::getLoopEntryState() const {
  if (transitions[0]->target != nullptr && transitions[0]->target->getStateType() == ATNStateType::STAR_LOOP_ENTRY) {
    return antlrcpp::downCast<StarLoopEntryState*>(transitions[0]->target);
  }
  return nullptr;
}
