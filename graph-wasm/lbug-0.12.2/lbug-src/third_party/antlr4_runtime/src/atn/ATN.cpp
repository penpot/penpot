/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/LL1Analyzer.h"
#include "Token.h"
#include "atn/RuleTransition.h"
#include "misc/IntervalSet.h"
#include "RuleContext.h"
#include "atn/DecisionState.h"
#include "Recognizer.h"
#include "atn/ATNType.h"
#include "Exceptions.h"
#include "support/CPPUtils.h"

#include "atn/ATN.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::internal;
using namespace antlrcpp;

ATN::ATN() : ATN(ATNType::LEXER, 0) {}

ATN::ATN(ATNType grammarType_, size_t maxTokenType_) : grammarType(grammarType_), maxTokenType(maxTokenType_) {}

ATN::~ATN() {
  for (ATNState *state : states) {
    delete state;
  }
}

misc::IntervalSet ATN::nextTokens(ATNState *s, RuleContext *ctx) const {
  LL1Analyzer analyzer(*this);
  return analyzer.LOOK(s, ctx);

}

misc::IntervalSet const& ATN::nextTokens(ATNState *s) const {
  if (!s->_nextTokenUpdated) {
    UniqueLock<Mutex> lock(_mutex);
    if (!s->_nextTokenUpdated) {
      s->_nextTokenWithinRule = nextTokens(s, nullptr);
      s->_nextTokenUpdated = true;
    }
  }
  return s->_nextTokenWithinRule;
}

void ATN::addState(ATNState *state) {
  if (state != nullptr) {
    //state->atn = this;
    state->stateNumber = static_cast<int>(states.size());
  }

  states.push_back(state);
}

void ATN::removeState(ATNState *state) {
  delete states.at(state->stateNumber);// just free mem, don't shift states in list
  states.at(state->stateNumber) = nullptr;
}

int ATN::defineDecisionState(DecisionState *s) {
  decisionToState.push_back(s);
  s->decision = static_cast<int>(decisionToState.size() - 1);
  return s->decision;
}

DecisionState *ATN::getDecisionState(size_t decision) const {
  if (!decisionToState.empty()) {
    return decisionToState[decision];
  }
  return nullptr;
}

size_t ATN::getNumberOfDecisions() const {
  return decisionToState.size();
}

misc::IntervalSet ATN::getExpectedTokens(size_t stateNumber, RuleContext *context) const {
  if (stateNumber == ATNState::INVALID_STATE_NUMBER || stateNumber >= states.size()) {
    throw IllegalArgumentException("Invalid state number.");
  }

  RuleContext *ctx = context;
  ATNState *s = states.at(stateNumber);
  misc::IntervalSet following = nextTokens(s);
  if (!following.contains(Token::EPSILON)) {
    return following;
  }

  misc::IntervalSet expected;
  expected.addAll(following);
  expected.remove(Token::EPSILON);
  while (ctx && ctx->invokingState != ATNState::INVALID_STATE_NUMBER && following.contains(Token::EPSILON)) {
    ATNState *invokingState = states.at(ctx->invokingState);
    const RuleTransition *rt = static_cast<const RuleTransition*>(invokingState->transitions[0].get());
    following = nextTokens(rt->followState);
    expected.addAll(following);
    expected.remove(Token::EPSILON);

    if (ctx->parent == nullptr) {
      break;
    }
    ctx = static_cast<RuleContext *>(ctx->parent);
  }

  if (following.contains(Token::EPSILON)) {
    expected.add(Token::EOF);
  }

  return expected;
}

std::string ATN::toString() const {
  std::stringstream ss;
  std::string type;
  switch (grammarType) {
    case ATNType::LEXER:
      type = "LEXER ";
      break;

    case ATNType::PARSER:
      type = "PARSER ";
      break;

    default:
      break;
  }
  ss << "(" << type << "ATN " << std::hex << this << std::dec << ") maxTokenType: " << maxTokenType << std::endl;
  ss << "states (" << states.size() << ") {" << std::endl;

  size_t index = 0;
  for (auto *state : states) {
    if (state == nullptr) {
      ss << "  " << index++ << ": nul" << std::endl;
    } else {
      std::string text = state->toString();
      ss << "  " << index++ << ": " << indent(text, "  ", false) << std::endl;
    }
  }

  index = 0;
  for (auto *state : decisionToState) {
    if (state == nullptr) {
      ss << "  " << index++ << ": nul" << std::endl;
    } else {
      std::string text = state->toString();
      ss << "  " << index++ << ": " << indent(text, "  ", false) << std::endl;
    }
  }

  ss << "}";

  return ss.str();
}

