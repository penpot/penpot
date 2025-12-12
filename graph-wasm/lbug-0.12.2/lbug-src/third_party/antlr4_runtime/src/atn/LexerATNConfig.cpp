/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "atn/DecisionState.h"
#include "atn/PredictionContext.h"
#include "SemanticContext.h"
#include "atn/LexerActionExecutor.h"

#include "support/CPPUtils.h"
#include "support/Casts.h"

#include "atn/LexerATNConfig.h"

using namespace antlr4::atn;
using namespace antlrcpp;

LexerATNConfig::LexerATNConfig(ATNState *state, int alt, Ref<const PredictionContext> context)
    : ATNConfig(state, alt, std::move(context)) {}

LexerATNConfig::LexerATNConfig(ATNState *state, int alt, Ref<const PredictionContext> context, Ref<const LexerActionExecutor> lexerActionExecutor)
    : ATNConfig(state, alt, std::move(context)), _lexerActionExecutor(std::move(lexerActionExecutor)) {}

LexerATNConfig::LexerATNConfig(LexerATNConfig const& other, ATNState *state)
    : ATNConfig(other, state), _lexerActionExecutor(other._lexerActionExecutor), _passedThroughNonGreedyDecision(checkNonGreedyDecision(other, state)) {}

LexerATNConfig::LexerATNConfig(LexerATNConfig const& other, ATNState *state, Ref<const LexerActionExecutor> lexerActionExecutor)
    : ATNConfig(other, state), _lexerActionExecutor(std::move(lexerActionExecutor)), _passedThroughNonGreedyDecision(checkNonGreedyDecision(other, state)) {}

LexerATNConfig::LexerATNConfig(LexerATNConfig const& other, ATNState *state, Ref<const PredictionContext> context)
    : ATNConfig(other, state, std::move(context)), _lexerActionExecutor(other._lexerActionExecutor), _passedThroughNonGreedyDecision(checkNonGreedyDecision(other, state)) {}

size_t LexerATNConfig::hashCode() const {
  size_t hashCode = misc::MurmurHash::initialize(7);
  hashCode = misc::MurmurHash::update(hashCode, state->stateNumber);
  hashCode = misc::MurmurHash::update(hashCode, alt);
  hashCode = misc::MurmurHash::update(hashCode, context);
  hashCode = misc::MurmurHash::update(hashCode, semanticContext);
  hashCode = misc::MurmurHash::update(hashCode, _passedThroughNonGreedyDecision ? 1 : 0);
  hashCode = misc::MurmurHash::update(hashCode, _lexerActionExecutor);
  hashCode = misc::MurmurHash::finish(hashCode, 6);
  return hashCode;
}

bool LexerATNConfig::operator==(const LexerATNConfig& other) const
{
  if (this == &other)
    return true;

  if (_passedThroughNonGreedyDecision != other._passedThroughNonGreedyDecision)
    return false;

  if (_lexerActionExecutor == nullptr)
    return other._lexerActionExecutor == nullptr;
  if (*_lexerActionExecutor != *(other._lexerActionExecutor)) {
    return false;
  }

  return ATNConfig::operator==(other);
}

bool LexerATNConfig::checkNonGreedyDecision(LexerATNConfig const& source, ATNState *target) {
  return source._passedThroughNonGreedyDecision ||
    (DecisionState::is(target) && downCast<DecisionState*>(target)->nonGreedy);
}
