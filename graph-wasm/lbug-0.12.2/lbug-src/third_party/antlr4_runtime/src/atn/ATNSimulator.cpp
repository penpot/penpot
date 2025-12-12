/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ATNSimulator.h"

#include "atn/ATNConfigSet.h"
#include "atn/ATNDeserializer.h"
#include "atn/ATNType.h"
#include "dfa/DFAState.h"

using namespace antlr4;
using namespace antlr4::dfa;
using namespace antlr4::atn;

const Ref<DFAState> ATNSimulator::ERROR = std::make_shared<DFAState>(std::numeric_limits<int>::max());

ATNSimulator::ATNSimulator(const ATN &atn, PredictionContextCache &sharedContextCache)
    : atn(atn), _sharedContextCache(sharedContextCache) {}

void ATNSimulator::clearDFA() {
  throw UnsupportedOperationException("This ATN simulator does not support clearing the DFA.");
}

PredictionContextCache& ATNSimulator::getSharedContextCache() const {
  return _sharedContextCache;
}

Ref<const PredictionContext> ATNSimulator::getCachedContext(const Ref<const PredictionContext> &context) {
  // This function must only be called with an active state lock, as we are going to change a shared structure.
  return PredictionContext::getCachedContext(context, getSharedContextCache());
}
