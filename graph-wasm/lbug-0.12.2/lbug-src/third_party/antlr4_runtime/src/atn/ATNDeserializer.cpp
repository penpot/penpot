/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ATNDeserializationOptions.h"

#include "atn/ATNType.h"
#include "atn/ATNState.h"
#include "atn/ATN.h"

#include "atn/LoopEndState.h"
#include "atn/DecisionState.h"
#include "atn/RuleStartState.h"
#include "atn/RuleStopState.h"
#include "atn/TokensStartState.h"
#include "atn/RuleTransition.h"
#include "atn/EpsilonTransition.h"
#include "atn/PlusLoopbackState.h"
#include "atn/PlusBlockStartState.h"
#include "atn/StarLoopbackState.h"
#include "atn/BasicBlockStartState.h"
#include "atn/BasicState.h"
#include "atn/BlockEndState.h"
#include "atn/StarLoopEntryState.h"

#include "atn/AtomTransition.h"
#include "atn/StarBlockStartState.h"
#include "atn/RangeTransition.h"
#include "atn/PredicateTransition.h"
#include "atn/PrecedencePredicateTransition.h"
#include "atn/ActionTransition.h"
#include "atn/SetTransition.h"
#include "atn/NotSetTransition.h"
#include "atn/WildcardTransition.h"
#include "atn/TransitionType.h"
#include "Token.h"

#include "misc/IntervalSet.h"
#include "Exceptions.h"
#include "support/CPPUtils.h"
#include "support/Casts.h"

#include "atn/LexerCustomAction.h"
#include "atn/LexerChannelAction.h"
#include "atn/LexerModeAction.h"
#include "atn/LexerMoreAction.h"
#include "atn/LexerPopModeAction.h"
#include "atn/LexerPushModeAction.h"
#include "atn/LexerSkipAction.h"
#include "atn/LexerTypeAction.h"

#include "atn/ATNDeserializer.h"

#include <cassert>
#include <string>
#include <vector>

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlrcpp;

namespace {

  void checkCondition(bool condition, std::string_view message) {
    if (!condition) {
      throw IllegalStateException(std::string(message));
    }
  }

  void checkCondition(bool condition) {
    checkCondition(condition, "");
  }

  /**
   * Analyze the {@link StarLoopEntryState} states in the specified ATN to set
   * the {@link StarLoopEntryState#isPrecedenceDecision} field to the
   * correct value.
   *
   * @param atn The ATN.
   */
  void markPrecedenceDecisions(const ATN &atn) {
    for (ATNState *state : atn.states) {
      if (!StarLoopEntryState::is(state)) {
        continue;
      }

      /* We analyze the ATN to determine if this ATN decision state is the
      * decision for the closure block that determines whether a
      * precedence rule should continue or complete.
      */
      if (atn.ruleToStartState[state->ruleIndex]->isLeftRecursiveRule) {
        ATNState *maybeLoopEndState = state->transitions[state->transitions.size() - 1]->target;
        if (LoopEndState::is(maybeLoopEndState)) {
          if (maybeLoopEndState->epsilonOnlyTransitions && RuleStopState::is(maybeLoopEndState->transitions[0]->target)) {
            downCast<StarLoopEntryState*>(state)->isPrecedenceDecision = true;
          }
        }
      }
    }
  }

  Ref<const LexerAction> lexerActionFactory(LexerActionType type, int data1, int data2) {
    switch (type) {
      case LexerActionType::CHANNEL:
        return std::make_shared<LexerChannelAction>(data1);

      case LexerActionType::CUSTOM:
        return std::make_shared<LexerCustomAction>(data1, data2);

      case LexerActionType::MODE:
        return std::make_shared< LexerModeAction>(data1);

      case LexerActionType::MORE:
        return LexerMoreAction::getInstance();

      case LexerActionType::POP_MODE:
        return LexerPopModeAction::getInstance();

      case LexerActionType::PUSH_MODE:
        return std::make_shared<LexerPushModeAction>(data1);

      case LexerActionType::SKIP:
        return LexerSkipAction::getInstance();

      case LexerActionType::TYPE:
        return std::make_shared<LexerTypeAction>(data1);

      default:
        throw IllegalArgumentException("The specified lexer action type " + std::to_string(static_cast<size_t>(type)) +
                                      " is not valid.");
    }
  }

  ConstTransitionPtr edgeFactory(const ATN &atn, TransitionType type, size_t trg, size_t arg1, size_t arg2,
                                        size_t arg3, const std::vector<misc::IntervalSet> &sets) {
    ATNState *target = atn.states[trg];
    switch (type) {
      case TransitionType::EPSILON:
        return std::make_unique<EpsilonTransition>(target);
      case TransitionType::RANGE:
        if (arg3 != 0) {
          return std::make_unique<RangeTransition>(target, Token::EOF, arg2);
        } else {
          return std::make_unique<RangeTransition>(target, arg1, arg2);
        }
      case TransitionType::RULE:
        return std::make_unique<RuleTransition>(downCast<RuleStartState*>(atn.states[arg1]), arg2, (int)arg3, target);
      case TransitionType::PREDICATE:
        return std::make_unique<PredicateTransition>(target, arg1, arg2, arg3 != 0);
      case TransitionType::PRECEDENCE:
        return std::make_unique<PrecedencePredicateTransition>(target, (int)arg1);
      case TransitionType::ATOM:
        if (arg3 != 0) {
          return std::make_unique<AtomTransition>(target, Token::EOF);
        } else {
          return std::make_unique<AtomTransition>(target, arg1);
        }
      case TransitionType::ACTION:
        return std::make_unique<ActionTransition>(target, arg1, arg2, arg3 != 0);
      case TransitionType::SET:
        return std::make_unique<SetTransition>(target, sets[arg1]);
      case TransitionType::NOT_SET:
        return std::make_unique<NotSetTransition>(target, sets[arg1]);
      case TransitionType::WILDCARD:
        return std::make_unique<WildcardTransition>(target);
    }

    throw IllegalArgumentException("The specified transition type is not valid.");
  }

  /* mem check: all created instances are freed in the d-tor of the ATN. */
  ATNState* stateFactory(ATNStateType type, size_t ruleIndex) {
    ATNState *s;
    switch (type) {
      case ATNStateType::INVALID:
        return nullptr;
      case ATNStateType::BASIC :
        s = new BasicState();
        break;
      case ATNStateType::RULE_START :
        s = new RuleStartState();
        break;
      case ATNStateType::BLOCK_START :
        s = new BasicBlockStartState();
        break;
      case ATNStateType::PLUS_BLOCK_START :
        s = new PlusBlockStartState();
        break;
      case ATNStateType::STAR_BLOCK_START :
        s = new StarBlockStartState();
        break;
      case ATNStateType::TOKEN_START :
        s = new TokensStartState();
        break;
      case ATNStateType::RULE_STOP :
        s = new RuleStopState();
        break;
      case ATNStateType::BLOCK_END :
        s = new BlockEndState();
        break;
      case ATNStateType::STAR_LOOP_BACK :
        s = new StarLoopbackState();
        break;
      case ATNStateType::STAR_LOOP_ENTRY :
        s = new StarLoopEntryState();
        break;
      case ATNStateType::PLUS_LOOP_BACK :
        s = new PlusLoopbackState();
        break;
      case ATNStateType::LOOP_END :
        s = new LoopEndState();
        break;
      default :
        std::string message = "The specified state type " + std::to_string(static_cast<size_t>(type)) + " is not valid.";
        throw IllegalArgumentException(message);
    }
    assert(s->getStateType() == type);
    s->ruleIndex = ruleIndex;
    return s;
  }

  ssize_t readUnicodeInt32(SerializedATNView data, int& p) {
    return static_cast<ssize_t>(data[p++]);
  }

  void deserializeSets(
    SerializedATNView data,
    int& p,
    std::vector<misc::IntervalSet>& sets) {
    size_t nsets = data[p++];
    sets.reserve(sets.size() + nsets);
    for (size_t i = 0; i < nsets; i++) {
      size_t nintervals = data[p++];
      misc::IntervalSet set;

      bool containsEof = data[p++] != 0;
      if (containsEof) {
        set.add(-1);
      }

      for (size_t j = 0; j < nintervals; j++) {
        auto a = readUnicodeInt32(data, p);
        auto b = readUnicodeInt32(data, p);
        set.add(a, b);
      }
      sets.push_back(set);
    }
  }

}

ATNDeserializer::ATNDeserializer() : ATNDeserializer(ATNDeserializationOptions::getDefaultOptions()) {}

ATNDeserializer::ATNDeserializer(ATNDeserializationOptions deserializationOptions) : _deserializationOptions(std::move(deserializationOptions)) {}

std::unique_ptr<ATN> ATNDeserializer::deserialize(SerializedATNView data) const {
  int p = 0;
  int version = data[p++];
  if (version != SERIALIZED_VERSION) {
    std::string reason = "Could not deserialize ATN with version" + std::to_string(version) + "(expected " + std::to_string(SERIALIZED_VERSION) + ").";

    throw UnsupportedOperationException(reason);
  }

  ATNType grammarType = (ATNType)data[p++];
  size_t maxTokenType = data[p++];
  auto atn = std::make_unique<ATN>(grammarType, maxTokenType);

  //
  // STATES
  //
  {
    std::vector<std::pair<LoopEndState*, size_t>> loopBackStateNumbers;
    std::vector<std::pair<BlockStartState*, size_t>> endStateNumbers;
    size_t nstates = data[p++];
    atn->states.reserve(nstates);
    loopBackStateNumbers.reserve(nstates);  // Reserve worst case size, its short lived.
    endStateNumbers.reserve(nstates);  // Reserve worst case size, its short lived.
    for (size_t i = 0; i < nstates; i++) {
      ATNStateType stype = static_cast<ATNStateType>(data[p++]);
      // ignore bad type of states
      if (stype == ATNStateType::INVALID) {
        atn->addState(nullptr);
        continue;
      }

      size_t ruleIndex = data[p++];
      ATNState *s = stateFactory(stype, ruleIndex);
      if (stype == ATNStateType::LOOP_END) { // special case
        int loopBackStateNumber = data[p++];
        loopBackStateNumbers.push_back({ downCast<LoopEndState*>(s),  loopBackStateNumber });
      } else if (BlockStartState::is(s)) {
        int endStateNumber = data[p++];
        endStateNumbers.push_back({ downCast<BlockStartState*>(s), endStateNumber });
      }
      atn->addState(s);
    }

    // delay the assignment of loop back and end states until we know all the state instances have been initialized
    for (auto &pair : loopBackStateNumbers) {
      pair.first->loopBackState = atn->states[pair.second];
    }

    for (auto &pair : endStateNumbers) {
      pair.first->endState = downCast<BlockEndState*>(atn->states[pair.second]);
    }
  }

  size_t numNonGreedyStates = data[p++];
  for (size_t i = 0; i < numNonGreedyStates; i++) {
    size_t stateNumber = data[p++];
    // The serialized ATN must be specifying the right states, so that the
    // cast below is correct.
    downCast<DecisionState*>(atn->states[stateNumber])->nonGreedy = true;
  }

  size_t numPrecedenceStates = data[p++];
  for (size_t i = 0; i < numPrecedenceStates; i++) {
    size_t stateNumber = data[p++];
    downCast<RuleStartState*>(atn->states[stateNumber])->isLeftRecursiveRule = true;
  }

  //
  // RULES
  //
  size_t nrules = data[p++];
  atn->ruleToStartState.reserve(nrules);
  for (size_t i = 0; i < nrules; i++) {
    size_t s = data[p++];
    // Also here, the serialized atn must ensure to point to the correct class type.
    RuleStartState *startState = downCast<RuleStartState*>(atn->states[s]);
    atn->ruleToStartState.push_back(startState);
    if (atn->grammarType == ATNType::LEXER) {
      size_t tokenType = data[p++];
      atn->ruleToTokenType.push_back(tokenType);
    }
  }

  atn->ruleToStopState.resize(nrules);
  for (ATNState *state : atn->states) {
    if (!RuleStopState::is(state)) {
      continue;
    }

    RuleStopState *stopState = downCast<RuleStopState*>(state);
    atn->ruleToStopState[state->ruleIndex] = stopState;
    atn->ruleToStartState[state->ruleIndex]->stopState = stopState;
  }

  //
  // MODES
  //
  size_t nmodes = data[p++];
  atn->modeToStartState.reserve(nmodes);
  for (size_t i = 0; i < nmodes; i++) {
    size_t s = data[p++];
    atn->modeToStartState.push_back(downCast<TokensStartState*>(atn->states[s]));
  }

  //
  // SETS
  //
  {
    std::vector<misc::IntervalSet> sets;

    deserializeSets(data, p, sets);
    sets.shrink_to_fit();

    //
    // EDGES
    //
    int nedges = data[p++];
    for (int i = 0; i < nedges; i++) {
      size_t src = data[p];
      size_t trg = data[p + 1];
      TransitionType ttype = static_cast<TransitionType>(data[p + 2]);
      size_t arg1 = data[p + 3];
      size_t arg2 = data[p + 4];
      size_t arg3 = data[p + 5];
      ConstTransitionPtr trans = edgeFactory(*atn, ttype, trg, arg1, arg2, arg3, sets);
      ATNState *srcState = atn->states[src];
      srcState->addTransition(std::move(trans));
      p += 6;
    }
  }
  // edges for rule stop states can be derived, so they aren't serialized
  for (ATNState *state : atn->states) {
    for (size_t i = 0; i < state->transitions.size(); i++) {
      const Transition *t = state->transitions[i].get();
      if (!RuleTransition::is(t)) {
        continue;
      }

      const RuleTransition *ruleTransition = downCast<const RuleTransition*>(t);
      size_t outermostPrecedenceReturn = INVALID_INDEX;
      if (atn->ruleToStartState[ruleTransition->target->ruleIndex]->isLeftRecursiveRule) {
        if (ruleTransition->precedence == 0) {
          outermostPrecedenceReturn = ruleTransition->target->ruleIndex;
        }
      }

      ConstTransitionPtr returnTransition = std::make_unique<EpsilonTransition>(ruleTransition->followState, outermostPrecedenceReturn);
      atn->ruleToStopState[ruleTransition->target->ruleIndex]->addTransition(std::move(returnTransition));
    }
  }

  for (ATNState *state : atn->states) {
    if (BlockStartState::is(state)) {
      BlockStartState *startState = downCast<BlockStartState*>(state);

      // we need to know the end state to set its start state
      if (startState->endState == nullptr) {
        throw IllegalStateException();
      }

      // block end states can only be associated to a single block start state
      if (startState->endState->startState != nullptr) {
        throw IllegalStateException();
      }

      startState->endState->startState = downCast<BlockStartState*>(state);
    }

    if (PlusLoopbackState::is(state)) {
      PlusLoopbackState *loopbackState = downCast<PlusLoopbackState*>(state);
      for (size_t i = 0; i < loopbackState->transitions.size(); i++) {
        ATNState *target = loopbackState->transitions[i]->target;
        if (PlusBlockStartState::is(target)) {
          (downCast<PlusBlockStartState*>(target))->loopBackState = loopbackState;
        }
      }
    } else if (StarLoopbackState::is(state)) {
      StarLoopbackState *loopbackState = downCast<StarLoopbackState*>(state);
      for (size_t i = 0; i < loopbackState->transitions.size(); i++) {
        ATNState *target = loopbackState->transitions[i]->target;
        if (StarLoopEntryState::is(target)) {
          downCast<StarLoopEntryState*>(target)->loopBackState = loopbackState;
        }
      }
    }
  }

  //
  // DECISIONS
  //
  size_t ndecisions = data[p++];
  atn->decisionToState.reserve(ndecisions);
  for (size_t i = 0; i < ndecisions; i++) {
    size_t s = data[p++];
    DecisionState *decState = downCast<DecisionState*>(atn->states[s]);
    if (decState == nullptr)
      throw IllegalStateException();

    atn->decisionToState.push_back(decState);
    decState->decision = static_cast<int>(i);
  }

  //
  // LEXER ACTIONS
  //
  if (atn->grammarType == ATNType::LEXER) {
    atn->lexerActions.resize(data[p++]);
    for (size_t i = 0; i < atn->lexerActions.size(); i++) {
      LexerActionType actionType = static_cast<LexerActionType>(data[p++]);
      int data1 = data[p++];
      int data2 = data[p++];
      atn->lexerActions[i] = lexerActionFactory(actionType, data1, data2);
    }
  }

  markPrecedenceDecisions(*atn);

  if (_deserializationOptions.isVerifyATN()) {
    verifyATN(*atn);
  }

  if (_deserializationOptions.isGenerateRuleBypassTransitions() && atn->grammarType == ATNType::PARSER) {
    atn->ruleToTokenType.resize(atn->ruleToStartState.size());
    for (size_t i = 0; i < atn->ruleToStartState.size(); i++) {
      atn->ruleToTokenType[i] = static_cast<int>(atn->maxTokenType + i + 1);
    }

    for (std::vector<RuleStartState*>::size_type i = 0; i < atn->ruleToStartState.size(); i++) {
      BasicBlockStartState *bypassStart = new BasicBlockStartState(); /* mem check: freed in ATN d-tor */
      bypassStart->ruleIndex = static_cast<int>(i);
      atn->addState(bypassStart);

      BlockEndState *bypassStop = new BlockEndState(); /* mem check: freed in ATN d-tor */
      bypassStop->ruleIndex = static_cast<int>(i);
      atn->addState(bypassStop);

      bypassStart->endState = bypassStop;
      atn->defineDecisionState(bypassStart);

      bypassStop->startState = bypassStart;

      ATNState *endState;
      const Transition *excludeTransition = nullptr;
      if (atn->ruleToStartState[i]->isLeftRecursiveRule) {
        // wrap from the beginning of the rule to the StarLoopEntryState
        endState = nullptr;
        for (ATNState *state : atn->states) {
          if (state->ruleIndex != i) {
            continue;
          }

          if (!StarLoopEntryState::is(state)) {
            continue;
          }

          ATNState *maybeLoopEndState = state->transitions[state->transitions.size() - 1]->target;
          if (!LoopEndState::is(maybeLoopEndState)) {
            continue;
          }

          if (maybeLoopEndState->epsilonOnlyTransitions && RuleStopState::is(maybeLoopEndState->transitions[0]->target)) {
            endState = state;
            break;
          }
        }

        if (endState == nullptr) {
          throw UnsupportedOperationException("Couldn't identify final state of the precedence rule prefix section.");

        }

        excludeTransition = (static_cast<StarLoopEntryState*>(endState))->loopBackState->transitions[0].get();
      } else {
        endState = atn->ruleToStopState[i];
      }

      // all non-excluded transitions that currently target end state need to target blockEnd instead
      for (ATNState *state : atn->states) {
        for (auto &transition : state->transitions) {
          if (transition.get() == excludeTransition) {
            continue;
          }

          if (transition->target == endState) {
            const_cast<Transition*>(transition.get())->target = bypassStop;
          }
        }
      }

      // all transitions leaving the rule start state need to leave blockStart instead
      while (atn->ruleToStartState[i]->transitions.size() > 0) {
        ConstTransitionPtr transition = atn->ruleToStartState[i]->removeTransition(atn->ruleToStartState[i]->transitions.size() - 1);
        bypassStart->addTransition(std::move(transition));
      }

      // link the new states
      atn->ruleToStartState[i]->addTransition(std::make_unique<EpsilonTransition>(bypassStart));
      bypassStop->addTransition(std::make_unique<EpsilonTransition>(endState));

      ATNState *matchState = new BasicState(); /* mem check: freed in ATN d-tor */
      atn->addState(matchState);
      matchState->addTransition(std::make_unique<AtomTransition>(bypassStop, atn->ruleToTokenType[i]));
      bypassStart->addTransition(std::make_unique<EpsilonTransition>(matchState));
    }

    if (_deserializationOptions.isVerifyATN()) {
      // reverify after modification
      verifyATN(*atn);
    }
  }

  return atn;
}

void ATNDeserializer::verifyATN(const ATN &atn) const {
  // verify assumptions
  for (ATNState *state : atn.states) {
    if (state == nullptr) {
      continue;
    }

    checkCondition(state->epsilonOnlyTransitions || state->transitions.size() <= 1);

    if (PlusBlockStartState::is(state)) {
      checkCondition((downCast<PlusBlockStartState*>(state))->loopBackState != nullptr);
    }

    if (StarLoopEntryState::is(state)) {
      StarLoopEntryState *starLoopEntryState = downCast<StarLoopEntryState*>(state);
      checkCondition(starLoopEntryState->loopBackState != nullptr);
      checkCondition(starLoopEntryState->transitions.size() == 2);

      if (StarBlockStartState::is(starLoopEntryState->transitions[0]->target)) {
        checkCondition(downCast<LoopEndState*>(starLoopEntryState->transitions[1]->target) != nullptr);
        checkCondition(!starLoopEntryState->nonGreedy);
      } else if (LoopEndState::is(starLoopEntryState->transitions[0]->target)) {
        checkCondition(StarBlockStartState::is(starLoopEntryState->transitions[1]->target));
        checkCondition(starLoopEntryState->nonGreedy);
      } else {
        throw IllegalStateException();
      }
    }

    if (StarLoopbackState::is(state)) {
      checkCondition(state->transitions.size() == 1);
      checkCondition(StarLoopEntryState::is(state->transitions[0]->target));
    }

    if (LoopEndState::is(state)) {
      checkCondition((downCast<LoopEndState*>(state))->loopBackState != nullptr);
    }

    if (RuleStartState::is(state)) {
      checkCondition((downCast<RuleStartState*>(state))->stopState != nullptr);
    }

    if (BlockStartState::is(state)) {
      checkCondition((downCast<BlockStartState*>(state))->endState != nullptr);
    }

    if (BlockEndState::is(state)) {
      checkCondition((downCast<BlockEndState*>(state))->startState != nullptr);
    }

    if (DecisionState::is(state)) {
      DecisionState *decisionState = downCast<DecisionState*>(state);
      checkCondition(decisionState->transitions.size() <= 1 || decisionState->decision >= 0);
    } else {
      checkCondition(state->transitions.size() <= 1 || RuleStopState::is(state));
    }
  }
}
