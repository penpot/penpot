/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "dfa/DFA.h"
#include "NoViableAltException.h"
#include "atn/DecisionState.h"
#include "ParserRuleContext.h"
#include "misc/IntervalSet.h"
#include "Parser.h"
#include "CommonTokenStream.h"
#include "atn/NotSetTransition.h"
#include "atn/AtomTransition.h"
#include "atn/RuleTransition.h"
#include "atn/PredicateTransition.h"
#include "atn/PrecedencePredicateTransition.h"
#include "atn/SingletonPredictionContext.h"
#include "atn/ActionTransition.h"
#include "atn/EpsilonTransition.h"
#include "atn/RuleStopState.h"
#include "atn/ATNConfigSet.h"
#include "atn/ATNConfig.h"
#include "internal/Synchronization.h"

#include "atn/StarLoopEntryState.h"
#include "atn/BlockStartState.h"
#include "atn/BlockEndState.h"

#include "misc/Interval.h"
#include "ANTLRErrorListener.h"

#include "Vocabulary.h"
#include "support/Arrays.h"
#include "support/Casts.h"

#include "atn/ParserATNSimulator.h"

#ifndef DEBUG_ATN
#define DEBUG_ATN 0
#endif
#ifndef TRACE_ATN_SIM
#define TRACE_ATN_SIM 0
#endif
#ifndef DFA_DEBUG
#define DFA_DEBUG 0
#endif
#ifndef RETRY_DEBUG
#define RETRY_DEBUG 0
#endif

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::internal;
using namespace antlrcpp;

const bool ParserATNSimulator::TURN_OFF_LR_LOOP_ENTRY_BRANCH_OPT = ParserATNSimulator::getLrLoopSetting();

ParserATNSimulator::ParserATNSimulator(const ATN &atn, std::vector<dfa::DFA> &decisionToDFA,
                                       PredictionContextCache &sharedContextCache)
: ParserATNSimulator(nullptr, atn, decisionToDFA, sharedContextCache) {
}

ParserATNSimulator::ParserATNSimulator(Parser *parser, const ATN &atn, std::vector<dfa::DFA> &decisionToDFA,
                                       PredictionContextCache &sharedContextCache)
: ParserATNSimulator(parser, atn, decisionToDFA, sharedContextCache, ParserATNSimulatorOptions()) {}

ParserATNSimulator::ParserATNSimulator(Parser *parser, const ATN &atn, std::vector<dfa::DFA> &decisionToDFA,
                                       PredictionContextCache &sharedContextCache,
                                       const ParserATNSimulatorOptions &options)
: ATNSimulator(atn, sharedContextCache), decisionToDFA(decisionToDFA), parser(parser),
  mergeCache(options.getPredictionContextMergeCacheOptions()) {
  InitializeInstanceFields();
}

void ParserATNSimulator::reset() {
}

void ParserATNSimulator::clearDFA() {
  int size = (int)decisionToDFA.size();
  decisionToDFA.clear();
  for (int d = 0; d < size; ++d) {
    decisionToDFA.push_back(dfa::DFA(atn.getDecisionState(d), d));
  }
}

size_t ParserATNSimulator::adaptivePredict(TokenStream *input, size_t decision, ParserRuleContext *outerContext) {

#if DEBUG_ATN == 1 || TRACE_ATN_SIM == 1
    std::cout << "adaptivePredict decision " << decision << " exec LA(1)==" << getLookaheadName(input) << " line "
      << input->LT(1)->getLine() << ":" << input->LT(1)->getCharPositionInLine() << std::endl;
#endif

  _input = input;
  _startIndex = input->index();
  _outerContext = outerContext;
  dfa::DFA &dfa = decisionToDFA[decision];
  _dfa = &dfa;

  ssize_t m = input->mark();
  size_t index = _startIndex;

  // Now we are certain to have a specific decision's DFA
  // But, do we still need an initial state?
  auto onExit = finally([this, input, index, m] {
    if (mergeCache.getOptions().getClearEveryN() != 0) {
      if (++_mergeCacheCounter == mergeCache.getOptions().getClearEveryN()) {
        mergeCache.clear();
        _mergeCacheCounter = 0;
      }
    }
    _dfa = nullptr;
    input->seek(index);
    input->release(m);
  });

  dfa::DFAState *s0;
  {
    SharedLock<SharedMutex> stateLock(atn._stateMutex);
    if (dfa.isPrecedenceDfa()) {
      // the start state for a precedence DFA depends on the current
      // parser precedence, and is provided by a DFA method.
      SharedLock<SharedMutex> edgeLock(atn._edgeMutex);
      s0 = dfa.getPrecedenceStartState(parser->getPrecedence());
    } else {
      // the start state for a "regular" DFA is just s0
      s0 = dfa.s0;
    }
  }

  if (s0 == nullptr) {
    auto s0_closure = computeStartState(dfa.atnStartState, &ParserRuleContext::EMPTY, false);
    std::unique_ptr<dfa::DFAState> newState;
    std::unique_ptr<dfa::DFAState> oldState;
    UniqueLock<SharedMutex> stateLock(atn._stateMutex);
    dfa::DFAState* ds0 = dfa.s0;
    if (dfa.isPrecedenceDfa()) {
      /* If this is a precedence DFA, we use applyPrecedenceFilter
       * to convert the computed start state to a precedence start
       * state. We then use DFA.setPrecedenceStartState to set the
       * appropriate start state for the precedence level rather
       * than simply setting DFA.s0.
       */
      ds0->configs = std::move(s0_closure); // not used for prediction but useful to know start configs anyway
      newState = std::make_unique<dfa::DFAState>(applyPrecedenceFilter(ds0->configs.get()));
      s0 = addDFAState(dfa, newState.get());
      UniqueLock<SharedMutex> edgeLock(atn._edgeMutex);
      dfa.setPrecedenceStartState(parser->getPrecedence(), s0);
    } else {
      newState = std::make_unique<dfa::DFAState>(std::move(s0_closure));
      s0 = addDFAState(dfa, newState.get());
      if (ds0 != s0) {
        oldState.reset(ds0);
        dfa.s0 = s0;
      }
    }
    if (s0 == newState.get()) {
      newState.release();
    }
  }

  // We can start with an existing DFA.
  size_t alt = execATN(dfa, s0, input, index, outerContext != nullptr ? outerContext : &ParserRuleContext::EMPTY);

  return alt;
}

size_t ParserATNSimulator::execATN(dfa::DFA &dfa, dfa::DFAState *s0, TokenStream *input, size_t startIndex,
                                   ParserRuleContext *outerContext) {

#if DEBUG_ATN == 1 || TRACE_ATN_SIM == 1
    std::cout << "execATN decision " << dfa.decision << ", DFA state " << s0->toString() <<
      ", LA(1)==" << getLookaheadName(input) <<
      " line " << input->LT(1)->getLine() << ":" << input->LT(1)->getCharPositionInLine() << std::endl;
#endif

  dfa::DFAState *previousD = s0;

#if DEBUG_ATN == 1
    std::cout << "s0 = " << s0->toString() << std::endl;
#endif

  size_t t = input->LA(1);

  while (true) { // while more work
    dfa::DFAState *D = getExistingTargetState(previousD, t);
    if (D == nullptr) {
      D = computeTargetState(dfa, previousD, t);
    }

    if (D == ERROR.get()) {
      // if any configs in previous dipped into outer context, that
      // means that input up to t actually finished entry rule
      // at least for SLL decision. Full LL doesn't dip into outer
      // so don't need special case.
      // We will get an error no matter what so delay until after
      // decision; better error message. Also, no reachable target
      // ATN states in SLL implies LL will also get nowhere.
      // If conflict in states that dip out, choose min since we
      // will get error no matter what.
      NoViableAltException e = noViableAlt(input, outerContext, previousD->configs.get(), startIndex, false);
      input->seek(startIndex);
      size_t alt = getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule(previousD->configs.get(), outerContext);
      if (alt != ATN::INVALID_ALT_NUMBER) {
        return alt;
      }

      throw e;
    }

    if (D->requiresFullContext && _mode != PredictionMode::SLL) {
      // IF PREDS, MIGHT RESOLVE TO SINGLE ALT => SLL (or syntax error)
      BitSet conflictingAlts;
      if (D->predicates.size() != 0) {
#if DEBUG_ATN == 1
          std::cout << "DFA state has preds in DFA sim LL failover" << std::endl;
#endif

        size_t conflictIndex = input->index();
        if (conflictIndex != startIndex) {
          input->seek(startIndex);
        }

        conflictingAlts = evalSemanticContext(D->predicates, outerContext, true);
        if (conflictingAlts.count() == 1) {
#if DEBUG_ATN == 1
            std::cout << "Full LL avoided" << std::endl;
#endif

          return conflictingAlts.nextSetBit(0);
        }

        if (conflictIndex != startIndex) {
          // restore the index so reporting the fallback to full
          // context occurs with the index at the correct spot
          input->seek(conflictIndex);
        }
      }

#if DFA_DEBUG == 1
        std::cout << "ctx sensitive state " << outerContext << " in " << D << std::endl;
#endif

      bool fullCtx = true;
      std::unique_ptr<ATNConfigSet> s0_closure = computeStartState(dfa.atnStartState, outerContext, fullCtx);
      reportAttemptingFullContext(dfa, conflictingAlts, D->configs.get(), startIndex, input->index());
      size_t alt = execATNWithFullContext(dfa, D, s0_closure.get(), input, startIndex, outerContext);
      return alt;
    }

    if (D->isAcceptState) {
      if (D->predicates.empty()) {
        return D->prediction;
      }

      size_t stopIndex = input->index();
      input->seek(startIndex);
      BitSet alts = evalSemanticContext(D->predicates, outerContext, true);
      switch (alts.count()) {
        case 0:
          throw noViableAlt(input, outerContext, D->configs.get(), startIndex, false);

        case 1:
          return alts.nextSetBit(0);

        default:
          // report ambiguity after predicate evaluation to make sure the correct
          // set of ambig alts is reported.
          reportAmbiguity(dfa, D, startIndex, stopIndex, false, alts, D->configs.get());
          return alts.nextSetBit(0);
      }
    }

    previousD = D;

    if (t != Token::EOF) {
      input->consume();
      t = input->LA(1);
    }
  }
}

dfa::DFAState *ParserATNSimulator::getExistingTargetState(dfa::DFAState *previousD, size_t t) {
  dfa::DFAState* retval;
  SharedLock<SharedMutex> edgeLock(atn._edgeMutex);
  auto iterator = previousD->edges.find(t);
  retval = (iterator == previousD->edges.end()) ? nullptr : iterator->second;
  return retval;
}

dfa::DFAState *ParserATNSimulator::computeTargetState(dfa::DFA &dfa, dfa::DFAState *previousD, size_t t) {
  std::unique_ptr<ATNConfigSet> reach = computeReachSet(previousD->configs.get(), t, false);
  if (reach == nullptr) {
    addDFAEdge(dfa, previousD, t, ERROR.get());
    return ERROR.get();
  }

  // create new target state; we'll add to DFA after it's complete
  dfa::DFAState *D = new dfa::DFAState(std::move(reach)); /* mem-check: managed by the DFA or deleted below, "reach" is no longer valid now. */
  size_t predictedAlt = getUniqueAlt(D->configs.get());

  if (predictedAlt != ATN::INVALID_ALT_NUMBER) {
    // NO CONFLICT, UNIQUELY PREDICTED ALT
    D->isAcceptState = true;
    D->configs->uniqueAlt = predictedAlt;
    D->prediction = predictedAlt;
  } else if (PredictionModeClass::hasSLLConflictTerminatingPrediction(_mode, D->configs.get())) {
    // MORE THAN ONE VIABLE ALTERNATIVE
    D->configs->conflictingAlts = getConflictingAlts(D->configs.get());
    D->requiresFullContext = true;
    // in SLL-only mode, we will stop at this state and return the minimum alt
    D->isAcceptState = true;
    D->prediction = D->configs->conflictingAlts.nextSetBit(0);
  }

  if (D->isAcceptState && D->configs->hasSemanticContext) {
    predicateDFAState(D, atn.getDecisionState(dfa.decision));
    if (D->predicates.size() != 0) {
      D->prediction = ATN::INVALID_ALT_NUMBER;
    }
  }

  // all adds to dfa are done after we've created full D state
  dfa::DFAState *state = addDFAEdge(dfa, previousD, t, D);
  if (state != D) {
    delete D; // If the new state exists already we don't need it and use the existing one instead.
  }
  return state;
}

void ParserATNSimulator::predicateDFAState(dfa::DFAState *dfaState, DecisionState *decisionState) {
  // We need to test all predicates, even in DFA states that
  // uniquely predict alternative.
  size_t nalts = decisionState->transitions.size();

  // Update DFA so reach becomes accept state with (predicate,alt)
  // pairs if preds found for conflicting alts
  BitSet altsToCollectPredsFrom = getConflictingAltsOrUniqueAlt(dfaState->configs.get());
  std::vector<Ref<const SemanticContext>> altToPred = getPredsForAmbigAlts(altsToCollectPredsFrom, dfaState->configs.get(), nalts);
  if (!altToPred.empty()) {
    dfaState->predicates = getPredicatePredictions(altsToCollectPredsFrom, altToPred);
    dfaState->prediction = ATN::INVALID_ALT_NUMBER; // make sure we use preds
  } else {
    // There are preds in configs but they might go away
    // when OR'd together like {p}? || NONE == NONE. If neither
    // alt has preds, resolve to min alt
    dfaState->prediction = altsToCollectPredsFrom.nextSetBit(0);
  }
}

size_t ParserATNSimulator::execATNWithFullContext(dfa::DFA &dfa, dfa::DFAState *D, ATNConfigSet *s0,
  TokenStream *input, size_t startIndex, ParserRuleContext *outerContext) {

#if TRACE_ATN_SIM == 1
    std::cout << "execATNWithFullContext " << s0->toString() << std::endl;
#endif

  bool fullCtx = true;
  bool foundExactAmbig = false;

  std::unique_ptr<ATNConfigSet> reach;
  ATNConfigSet *previous = s0;
  input->seek(startIndex);
  size_t t = input->LA(1);
  size_t predictedAlt;

  while (true) {
    reach = computeReachSet(previous, t, fullCtx);
    if (reach == nullptr) {
      // if any configs in previous dipped into outer context, that
      // means that input up to t actually finished entry rule
      // at least for LL decision. Full LL doesn't dip into outer
      // so don't need special case.
      // We will get an error no matter what so delay until after
      // decision; better error message. Also, no reachable target
      // ATN states in SLL implies LL will also get nowhere.
      // If conflict in states that dip out, choose min since we
      // will get error no matter what.
      NoViableAltException e = noViableAlt(input, outerContext, previous, startIndex, previous != s0);
      input->seek(startIndex);
      size_t alt = getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule(previous, outerContext);
      if (alt != ATN::INVALID_ALT_NUMBER) {
        return alt;
      }
      throw e;
    }
    if (previous != s0) // Don't delete the start set.
        delete previous;
    previous = nullptr;

    std::vector<BitSet> altSubSets = PredictionModeClass::getConflictingAltSubsets(reach.get());
    reach->uniqueAlt = getUniqueAlt(reach.get());
    // unique prediction?
    if (reach->uniqueAlt != ATN::INVALID_ALT_NUMBER) {
      predictedAlt = reach->uniqueAlt;
      break;
    }
    if (_mode != PredictionMode::LL_EXACT_AMBIG_DETECTION) {
      predictedAlt = PredictionModeClass::resolvesToJustOneViableAlt(altSubSets);
      if (predictedAlt != ATN::INVALID_ALT_NUMBER) {
        break;
      }
    } else {
      // In exact ambiguity mode, we never try to terminate early.
      // Just keeps scarfing until we know what the conflict is
      if (PredictionModeClass::allSubsetsConflict(altSubSets) && PredictionModeClass::allSubsetsEqual(altSubSets)) {
        foundExactAmbig = true;
        predictedAlt = PredictionModeClass::getSingleViableAlt(altSubSets);
        break;
      }
      // else there are multiple non-conflicting subsets or
      // we're not sure what the ambiguity is yet.
      // So, keep going.
    }
    previous = reach.release();

    if (t != Token::EOF) {
      input->consume();
      t = input->LA(1);
    }
  }

  // If the configuration set uniquely predicts an alternative,
  // without conflict, then we know that it's a full LL decision
  // not SLL.
  if (reach->uniqueAlt != ATN::INVALID_ALT_NUMBER) {
    reportContextSensitivity(dfa, predictedAlt, reach.get(), startIndex, input->index());
    return predictedAlt;
  }

  // We do not check predicates here because we have checked them
  // on-the-fly when doing full context prediction.

  /*
   In non-exact ambiguity detection mode, we might	actually be able to
   detect an exact ambiguity, but I'm not going to spend the cycles
   needed to check. We only emit ambiguity warnings in exact ambiguity
   mode.

   For example, we might know that we have conflicting configurations.
   But, that does not mean that there is no way forward without a
   conflict. It's possible to have nonconflicting alt subsets as in:

   LL altSubSets=[{1, 2}, {1, 2}, {1}, {1, 2}]

   from

   [(17,1,[5 $]), (13,1,[5 10 $]), (21,1,[5 10 $]), (11,1,[$]),
   (13,2,[5 10 $]), (21,2,[5 10 $]), (11,2,[$])]

   In this case, (17,1,[5 $]) indicates there is some next sequence that
   would resolve this without conflict to alternative 1. Any other viable
   next sequence, however, is associated with a conflict.  We stop
   looking for input because no amount of further lookahead will alter
   the fact that we should predict alternative 1.  We just can't say for
   sure that there is an ambiguity without looking further.
   */
  reportAmbiguity(dfa, D, startIndex, input->index(), foundExactAmbig, reach->getAlts(), reach.get());

  return predictedAlt;
}

std::unique_ptr<ATNConfigSet> ParserATNSimulator::computeReachSet(ATNConfigSet *closure_, size_t t, bool fullCtx) {

  std::unique_ptr<ATNConfigSet> intermediate(new ATNConfigSet(fullCtx));

  /* Configurations already in a rule stop state indicate reaching the end
   * of the decision rule (local context) or end of the start rule (full
   * context). Once reached, these configurations are never updated by a
   * closure operation, so they are handled separately for the performance
   * advantage of having a smaller intermediate set when calling closure.
   *
   * For full-context reach operations, separate handling is required to
   * ensure that the alternative matching the longest overall sequence is
   * chosen when multiple such configurations can match the input.
   */
  std::vector<Ref<ATNConfig>> skippedStopStates;

  // First figure out where we can reach on input t
  for (const auto &c : closure_->configs) {
    if (RuleStopState::is(c->state)) {
      assert(c->context->isEmpty());

      if (fullCtx || t == Token::EOF) {
        skippedStopStates.push_back(c);
      }

      continue;
    }

    size_t n = c->state->transitions.size();
    for (size_t ti = 0; ti < n; ti++) { // for each transition
      const Transition *trans = c->state->transitions[ti].get();
      ATNState *target = getReachableTarget(trans, (int)t);
      if (target != nullptr) {
        intermediate->add(std::make_shared<ATNConfig>(*c, target), &mergeCache);
      }
    }
  }

  // Now figure out where the reach operation can take us...
  std::unique_ptr<ATNConfigSet> reach;

  /* This block optimizes the reach operation for intermediate sets which
   * trivially indicate a termination state for the overall
   * adaptivePredict operation.
   *
   * The conditions assume that intermediate
   * contains all configurations relevant to the reach set, but this
   * condition is not true when one or more configurations have been
   * withheld in skippedStopStates, or when the current symbol is EOF.
   */
  if (skippedStopStates.empty() && t != Token::EOF) {
    if (intermediate->size() == 1) {
      // Don't pursue the closure if there is just one state.
      // It can only have one alternative; just add to result
      // Also don't pursue the closure if there is unique alternative
      // among the configurations.
      reach = std::move(intermediate);
    } else if (getUniqueAlt(intermediate.get()) != ATN::INVALID_ALT_NUMBER) {
      // Also don't pursue the closure if there is unique alternative
      // among the configurations.
      reach = std::move(intermediate);
    }
  }

  /* If the reach set could not be trivially determined, perform a closure
   * operation on the intermediate set to compute its initial value.
   */
  if (reach == nullptr) {
    reach.reset(new ATNConfigSet(fullCtx));
    ATNConfig::Set closureBusy;

    bool treatEofAsEpsilon = t == Token::EOF;
    for (const auto &c : intermediate->configs) {
      closure(c, reach.get(), closureBusy, false, fullCtx, treatEofAsEpsilon);
    }
  }

  if (t == IntStream::EOF) {
    /* After consuming EOF no additional input is possible, so we are
     * only interested in configurations which reached the end of the
     * decision rule (local context) or end of the start rule (full
     * context). Update reach to contain only these configurations. This
     * handles both explicit EOF transitions in the grammar and implicit
     * EOF transitions following the end of the decision or start rule.
     *
     * When reach==intermediate, no closure operation was performed. In
     * this case, removeAllConfigsNotInRuleStopState needs to check for
     * reachable rule stop states as well as configurations already in
     * a rule stop state.
     *
     * This is handled before the configurations in skippedStopStates,
     * because any configurations potentially added from that list are
     * already guaranteed to meet this condition whether or not it's
     * required.
     */
    ATNConfigSet *temp = removeAllConfigsNotInRuleStopState(reach.get(), *reach == *intermediate);
    if (temp != reach.get())
      reach.reset(temp); // We got a new set, so use that.
  }

  /* If skippedStopStates is not null, then it contains at least one
   * configuration. For full-context reach operations, these
   * configurations reached the end of the start rule, in which case we
   * only add them back to reach if no configuration during the current
   * closure operation reached such a state. This ensures adaptivePredict
   * chooses an alternative matching the longest overall sequence when
   * multiple alternatives are viable.
   */
  if (skippedStopStates.size() > 0 && (!fullCtx || !PredictionModeClass::hasConfigInRuleStopState(reach.get()))) {
    assert(!skippedStopStates.empty());

    for (const auto &c : skippedStopStates) {
      reach->add(c, &mergeCache);
    }
  }

#if DEBUG_ATN == 1 || TRACE_ATN_SIM == 1
    std::cout << "computeReachSet " << closure_->toString() << " -> " << reach->toString() << std::endl;
#endif

    if (reach->isEmpty()) {
    return nullptr;
  }
  return reach;
}

ATNConfigSet* ParserATNSimulator::removeAllConfigsNotInRuleStopState(ATNConfigSet *configs,
  bool lookToEndOfRule) {
  if (PredictionModeClass::allConfigsInRuleStopStates(configs)) {
    return configs;
  }

  ATNConfigSet *result = new ATNConfigSet(configs->fullCtx); /* mem-check: released by caller */

  for (const auto &config : configs->configs) {
    if (config->state != nullptr && config->state->getStateType() == ATNStateType::RULE_STOP) {
      result->add(config, &mergeCache);
      continue;
    }

    if (lookToEndOfRule && config->state->epsilonOnlyTransitions) {
      misc::IntervalSet nextTokens = atn.nextTokens(config->state);
      if (nextTokens.contains(Token::EPSILON)) {
        ATNState *endOfRuleState = atn.ruleToStopState[config->state->ruleIndex];
        result->add(std::make_shared<ATNConfig>(*config, endOfRuleState), &mergeCache);
      }
    }
  }

  return result;
}

std::unique_ptr<ATNConfigSet> ParserATNSimulator::computeStartState(ATNState *p, RuleContext *ctx, bool fullCtx) {
  // always at least the implicit call to start rule
  Ref<const PredictionContext> initialContext = PredictionContext::fromRuleContext(atn, ctx);
  std::unique_ptr<ATNConfigSet> configs(new ATNConfigSet(fullCtx));

#if DEBUG_ATN == 1 || TRACE_ATN_SIM == 1
    std::cout << "computeStartState from ATN state " << p->toString() << " initialContext=" << initialContext->toString() << std::endl;
#endif

    for (size_t i = 0; i < p->transitions.size(); i++) {
    ATNState *target = p->transitions[i]->target;
    Ref<ATNConfig> c = std::make_shared<ATNConfig>(target, (int)i + 1, initialContext);
    ATNConfig::Set closureBusy;
    closure(c, configs.get(), closureBusy, true, fullCtx, false);
  }

  return configs;
}

std::unique_ptr<ATNConfigSet> ParserATNSimulator::applyPrecedenceFilter(ATNConfigSet *configs) {
  std::map<size_t, Ref<const PredictionContext>> statesFromAlt1;
  std::unique_ptr<ATNConfigSet> configSet(new ATNConfigSet(configs->fullCtx));
  for (const auto &config : configs->configs) {
    // handle alt 1 first
    if (config->alt != 1) {
      continue;
    }

    Ref<const SemanticContext> updatedContext = config->semanticContext->evalPrecedence(parser, _outerContext);
    if (updatedContext == nullptr) {
      // the configuration was eliminated
      continue;
    }

    statesFromAlt1[config->state->stateNumber] = config->context;
    if (updatedContext != config->semanticContext) {
      configSet->add(std::make_shared<ATNConfig>(*config, updatedContext), &mergeCache);
    }
    else {
      configSet->add(config, &mergeCache);
    }
  }

  for (const auto &config : configs->configs) {
    if (config->alt == 1) {
      // already handled
      continue;
    }

    if (!config->isPrecedenceFilterSuppressed()) {
      /* In the future, this elimination step could be updated to also
       * filter the prediction context for alternatives predicting alt>1
       * (basically a graph subtraction algorithm).
       */
      auto iterator = statesFromAlt1.find(config->state->stateNumber);
      if (iterator != statesFromAlt1.end() && *iterator->second == *config->context) {
        // eliminated
        continue;
      }
    }

    configSet->add(config, &mergeCache);
  }

  return configSet;
}

atn::ATNState* ParserATNSimulator::getReachableTarget(const Transition *trans, size_t ttype) {
  if (trans->matches(ttype, 0, atn.maxTokenType)) {
    return trans->target;
  }

  return nullptr;
}

// Note that caller must memory manage the returned value from this function
std::vector<Ref<const SemanticContext>> ParserATNSimulator::getPredsForAmbigAlts(const BitSet &ambigAlts,
  ATNConfigSet *configs, size_t nalts) {
  // REACH=[1|1|[]|0:0, 1|2|[]|0:1]
  /* altToPred starts as an array of all null contexts. The entry at index i
   * corresponds to alternative i. altToPred[i] may have one of three values:
   *   1. null: no ATNConfig c is found such that c.alt==i
   *   2. SemanticContext.NONE: At least one ATNConfig c exists such that
   *      c.alt==i and c.semanticContext==SemanticContext.NONE. In other words,
   *      alt i has at least one un-predicated config.
   *   3. Non-NONE Semantic Context: There exists at least one, and for all
   *      ATNConfig c such that c.alt==i, c.semanticContext!=SemanticContext.NONE.
   *
   * From this, it is clear that NONE||anything==NONE.
   */
  std::vector<Ref<const SemanticContext>> altToPred(nalts + 1);

  for (const auto &c : configs->configs) {
    if (ambigAlts.test(c->alt)) {
      altToPred[c->alt] = SemanticContext::Or(altToPred[c->alt], c->semanticContext);
    }
  }

  size_t nPredAlts = 0;
  for (size_t i = 1; i <= nalts; i++) {
    if (altToPred[i] == nullptr) {
      altToPred[i] = SemanticContext::Empty::Instance;
    } else if (altToPred[i] != SemanticContext::Empty::Instance) {
      nPredAlts++;
    }
  }

  // nonambig alts are null in altToPred
  if (nPredAlts == 0) {
    altToPred.clear();
  }
#if DEBUG_ATN == 1
    std::cout << "getPredsForAmbigAlts result " << Arrays::toString(altToPred) << std::endl;
#endif

  return altToPred;
}

std::vector<dfa::DFAState::PredPrediction> ParserATNSimulator::getPredicatePredictions(const antlrcpp::BitSet &ambigAlts,
                                                                                       const std::vector<Ref<const SemanticContext>> &altToPred) {
  bool containsPredicate = std::find_if(altToPred.begin(), altToPred.end(), [](const Ref<const SemanticContext> &context) {
    return context != SemanticContext::Empty::Instance;
  }) != altToPred.end();
  std::vector<dfa::DFAState::PredPrediction> pairs;
  if (containsPredicate) {
    for (size_t i = 1; i < altToPred.size(); i++) {
      const auto &pred = altToPred[i];
      assert(pred != nullptr); // unpredicted is indicated by SemanticContext.NONE
      if (ambigAlts.test(i)) {
        pairs.emplace_back(pred, static_cast<int>(i));
      }
    }
  }
  return pairs;
}

size_t ParserATNSimulator::getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule(ATNConfigSet *configs,
  ParserRuleContext *outerContext)
{
  std::pair<ATNConfigSet *, ATNConfigSet *> sets = splitAccordingToSemanticValidity(configs, outerContext);
  std::unique_ptr<ATNConfigSet> semValidConfigs(sets.first);
  std::unique_ptr<ATNConfigSet> semInvalidConfigs(sets.second);
  size_t alt = getAltThatFinishedDecisionEntryRule(semValidConfigs.get());
  if (alt != ATN::INVALID_ALT_NUMBER) { // semantically/syntactically viable path exists
    return alt;
  }
  // Is there a syntactically valid path with a failed pred?
  if (!semInvalidConfigs->configs.empty()) {
    alt = getAltThatFinishedDecisionEntryRule(semInvalidConfigs.get());
    if (alt != ATN::INVALID_ALT_NUMBER) { // syntactically viable path exists
      return alt;
    }
  }
  return ATN::INVALID_ALT_NUMBER;
}

size_t ParserATNSimulator::getAltThatFinishedDecisionEntryRule(ATNConfigSet *configs) {
  misc::IntervalSet alts;
  for (const auto &c : configs->configs) {
    if (c->getOuterContextDepth() > 0 || (c->state != nullptr && c->state->getStateType() == ATNStateType::RULE_STOP && c->context->hasEmptyPath())) {
      alts.add(c->alt);
    }
  }
  if (alts.size() == 0) {
    return ATN::INVALID_ALT_NUMBER;
  }
  return alts.getMinElement();
}

std::pair<ATNConfigSet *, ATNConfigSet *> ParserATNSimulator::splitAccordingToSemanticValidity(ATNConfigSet *configs,
  ParserRuleContext *outerContext) {

  // mem-check: both pointers must be freed by the caller.
  ATNConfigSet *succeeded(new ATNConfigSet(configs->fullCtx));
  ATNConfigSet *failed(new ATNConfigSet(configs->fullCtx));
  for (const auto &c : configs->configs) {
    if (c->semanticContext != SemanticContext::Empty::Instance) {
      bool predicateEvaluationResult = evalSemanticContext(c->semanticContext, outerContext, c->alt, configs->fullCtx);
      if (predicateEvaluationResult) {
        succeeded->add(c);
      } else {
        failed->add(c);
      }
    } else {
      succeeded->add(c);
    }
  }
  return { succeeded, failed };
}

BitSet ParserATNSimulator::evalSemanticContext(const std::vector<dfa::DFAState::PredPrediction> &predPredictions,
                                               ParserRuleContext *outerContext, bool complete) {
  BitSet predictions;
  for (const auto &prediction : predPredictions) {
    if (prediction.pred == SemanticContext::Empty::Instance) {
      predictions.set(prediction.alt);
      if (!complete) {
        break;
      }
      continue;
    }

    bool fullCtx = false; // in dfa
    bool predicateEvaluationResult = evalSemanticContext(prediction.pred, outerContext, prediction.alt, fullCtx);
#if DEBUG_ATN == 1 || DFA_DEBUG == 1
      std::cout << "eval pred " << prediction.toString() << " = " << predicateEvaluationResult << std::endl;
#endif

    if (predicateEvaluationResult) {
#if DEBUG_ATN == 1 || DFA_DEBUG == 1
        std::cout << "PREDICT " << prediction.alt << std::endl;
#endif

      predictions.set(prediction.alt);
      if (!complete) {
        break;
      }
    }
  }

  return predictions;
}

bool ParserATNSimulator::evalSemanticContext(Ref<const SemanticContext> const& pred, ParserRuleContext *parserCallStack,
                                             size_t /*alt*/, bool /*fullCtx*/) {
  return pred->eval(parser, parserCallStack);
}

void ParserATNSimulator::closure(Ref<ATNConfig> const& config, ATNConfigSet *configs, ATNConfig::Set &closureBusy,
                                 bool collectPredicates, bool fullCtx, bool treatEofAsEpsilon) {
  const int initialDepth = 0;
  closureCheckingStopState(config, configs, closureBusy, collectPredicates, fullCtx, initialDepth, treatEofAsEpsilon);

  assert(!fullCtx || !configs->dipsIntoOuterContext);
}

void ParserATNSimulator::closureCheckingStopState(Ref<ATNConfig> const& config, ATNConfigSet *configs,
  ATNConfig::Set &closureBusy, bool collectPredicates, bool fullCtx, int depth, bool treatEofAsEpsilon) {

#if TRACE_ATN_SIM == 1
    std::cout << "closure(" << config->toString(true) << ")" << std::endl;
#endif

  if (config->state != nullptr && config->state->getStateType() == ATNStateType::RULE_STOP) {
    // We hit rule end. If we have context info, use it
    // run thru all possible stack tops in ctx
    if (!config->context->isEmpty()) {
      for (size_t i = 0; i < config->context->size(); i++) {
        if (config->context->getReturnState(i) == PredictionContext::EMPTY_RETURN_STATE) {
          if (fullCtx) {
            configs->add(std::make_shared<ATNConfig>(*config, config->state, PredictionContext::EMPTY), &mergeCache);
            continue;
          } else {
            // we have no context info, just chase follow links (if greedy)
#if DEBUG_ATN == 1
              std::cout << "FALLING off rule " << getRuleName(config->state->ruleIndex) << std::endl;
#endif
            closure_(config, configs, closureBusy, collectPredicates, fullCtx, depth, treatEofAsEpsilon);
          }
          continue;
        }
        ATNState *returnState = atn.states[config->context->getReturnState(i)];
        Ref<const PredictionContext> newContext = config->context->getParent(i); // "pop" return state
        Ref<ATNConfig> c = std::make_shared<ATNConfig>(returnState, config->alt, newContext, config->semanticContext);
        // While we have context to pop back from, we may have
        // gotten that context AFTER having falling off a rule.
        // Make sure we track that we are now out of context.
        //
        // This assignment also propagates the
        // isPrecedenceFilterSuppressed() value to the new
        // configuration.
        c->reachesIntoOuterContext = config->reachesIntoOuterContext;
        assert(depth > INT_MIN);

        closureCheckingStopState(c, configs, closureBusy, collectPredicates, fullCtx, depth - 1, treatEofAsEpsilon);
      }
      return;
    } else if (fullCtx) {
      // reached end of start rule
      configs->add(config, &mergeCache);
      return;
    } else {
      // else if we have no context info, just chase follow links (if greedy)
    }
  }

  closure_(config, configs, closureBusy, collectPredicates, fullCtx, depth, treatEofAsEpsilon);
}

void ParserATNSimulator::closure_(Ref<ATNConfig> const& config, ATNConfigSet *configs, ATNConfig::Set &closureBusy,
                                  bool collectPredicates, bool fullCtx, int depth, bool treatEofAsEpsilon) {
  ATNState *p = config->state;
  // optimization
  if (!p->epsilonOnlyTransitions) {
    // make sure to not return here, because EOF transitions can act as
    // both epsilon transitions and non-epsilon transitions.
    configs->add(config, &mergeCache);
  }

  for (size_t i = 0; i < p->transitions.size(); i++) {
    if (i == 0 && canDropLoopEntryEdgeInLeftRecursiveRule(config.get()))
      continue;

    const Transition *t = p->transitions[i].get();
    bool continueCollecting = !(t != nullptr && t->getTransitionType() == TransitionType::ACTION) && collectPredicates;
    Ref<ATNConfig> c = getEpsilonTarget(config, t, continueCollecting, depth == 0, fullCtx, treatEofAsEpsilon);
    if (c != nullptr) {
      int newDepth = depth;
      if (config->state != nullptr && config->state->getStateType() == ATNStateType::RULE_STOP) {
        assert(!fullCtx);

        // target fell off end of rule; mark resulting c as having dipped into outer context
        // We can't get here if incoming config was rule stop and we had context
        // track how far we dip into outer context.  Might
        // come in handy and we avoid evaluating context dependent
        // preds if this is > 0.

        if (closureBusy.count(c) > 0) {
          // avoid infinite recursion for right-recursive rules
          continue;
        }
        closureBusy.insert(c);

        if (_dfa != nullptr && _dfa->isPrecedenceDfa()) {
          size_t outermostPrecedenceReturn = downCast<const EpsilonTransition *>(t)->outermostPrecedenceReturn();
          if (outermostPrecedenceReturn == _dfa->atnStartState->ruleIndex) {
            c->setPrecedenceFilterSuppressed(true);
          }
        }

        c->reachesIntoOuterContext++;

        if (!t->isEpsilon()) {
          // avoid infinite recursion for EOF* and EOF+
          if (closureBusy.count(c) == 0) {
            closureBusy.insert(c);
          } else {
            continue;
          }
        }

        configs->dipsIntoOuterContext = true; // TODO: can remove? only care when we add to set per middle of this method
        assert(newDepth > INT_MIN);

        newDepth--;
#if DFA_DEBUG == 1
          std::cout << "dips into outer ctx: " << c << std::endl;
#endif

      } else  if (!t->isEpsilon()) {
        // avoid infinite recursion for EOF* and EOF+
        if (closureBusy.count(c) == 0) {
          closureBusy.insert(c);
        } else {
          continue;
        }
      }

      if (t != nullptr && t->getTransitionType() == TransitionType::RULE) {
        // latch when newDepth goes negative - once we step out of the entry context we can't return
        if (newDepth >= 0) {
          newDepth++;
        }
      }

      closureCheckingStopState(c, configs, closureBusy, continueCollecting, fullCtx, newDepth, treatEofAsEpsilon);
    }
  }
}

bool ParserATNSimulator::canDropLoopEntryEdgeInLeftRecursiveRule(ATNConfig *config) const {
  if (TURN_OFF_LR_LOOP_ENTRY_BRANCH_OPT)
    return false;

  ATNState *p = config->state;

  // First check to see if we are in StarLoopEntryState generated during
  // left-recursion elimination. For efficiency, also check if
  // the context has an empty stack case. If so, it would mean
  // global FOLLOW so we can't perform optimization
  if (p->getStateType() != ATNStateType::STAR_LOOP_ENTRY ||
      !((StarLoopEntryState *)p)->isPrecedenceDecision || // Are we the special loop entry/exit state?
      config->context->isEmpty() ||                      // If SLL wildcard
      config->context->hasEmptyPath())
  {
    return false;
  }

  // Require all return states to return back to the same rule
  // that p is in.
  size_t numCtxs = config->context->size();
  for (size_t i = 0; i < numCtxs; i++) { // for each stack context
    ATNState *returnState = atn.states[config->context->getReturnState(i)];
    if (returnState->ruleIndex != p->ruleIndex)
      return false;
  }

  BlockStartState *decisionStartState = (BlockStartState *)p->transitions[0]->target;
  size_t blockEndStateNum = decisionStartState->endState->stateNumber;
  BlockEndState *blockEndState = (BlockEndState *)atn.states[blockEndStateNum];

  // Verify that the top of each stack context leads to loop entry/exit
  // state through epsilon edges and w/o leaving rule.
  for (size_t i = 0; i < numCtxs; i++) {                           // for each stack context
    size_t returnStateNumber = config->context->getReturnState(i);
    ATNState *returnState = atn.states[returnStateNumber];
    // All states must have single outgoing epsilon edge.
    if (returnState->transitions.size() != 1 || !returnState->transitions[0]->isEpsilon())
    {
      return false;
    }

    // Look for prefix op case like 'not expr', (' type ')' expr
    ATNState *returnStateTarget = returnState->transitions[0]->target;
    if (returnState->getStateType() == ATNStateType::BLOCK_END && returnStateTarget == p) {
      continue;
    }

    // Look for 'expr op expr' or case where expr's return state is block end
    // of (...)* internal block; the block end points to loop back
    // which points to p but we don't need to check that
    if (returnState == blockEndState) {
      continue;
    }

    // Look for ternary expr ? expr : expr. The return state points at block end,
    // which points at loop entry state
    if (returnStateTarget == blockEndState) {
      continue;
    }

    // Look for complex prefix 'between expr and expr' case where 2nd expr's
    // return state points at block end state of (...)* internal block
    if (returnStateTarget->getStateType() == ATNStateType::BLOCK_END &&
        returnStateTarget->transitions.size() == 1 &&
        returnStateTarget->transitions[0]->isEpsilon() &&
        returnStateTarget->transitions[0]->target == p)
    {
      continue;
    }

    // Anything else ain't conforming.
    return false;
  }

  return true;
}

std::string ParserATNSimulator::getRuleName(size_t index) {
  if (parser != nullptr) {
    return parser->getRuleNames()[index];
  }
  return "<rule " + std::to_string(index) + ">";
}

Ref<ATNConfig> ParserATNSimulator::getEpsilonTarget(Ref<ATNConfig> const& config, const Transition *t, bool collectPredicates,
                                                    bool inContext, bool fullCtx, bool treatEofAsEpsilon) {
  switch (t->getTransitionType()) {
    case TransitionType::RULE:
      return ruleTransition(config, static_cast<const RuleTransition*>(t));

    case TransitionType::PRECEDENCE:
      return precedenceTransition(config, static_cast<const PrecedencePredicateTransition*>(t), collectPredicates, inContext, fullCtx);

    case TransitionType::PREDICATE:
      return predTransition(config, static_cast<const PredicateTransition*>(t), collectPredicates, inContext, fullCtx);

    case TransitionType::ACTION:
      return actionTransition(config, static_cast<const ActionTransition*>(t));

    case TransitionType::EPSILON:
      return std::make_shared<ATNConfig>(*config, t->target);

    case TransitionType::ATOM:
    case TransitionType::RANGE:
    case TransitionType::SET:
      // EOF transitions act like epsilon transitions after the first EOF
      // transition is traversed
      if (treatEofAsEpsilon) {
        if (t->matches(Token::EOF, 0, 1)) {
          return std::make_shared<ATNConfig>(*config, t->target);
        }
      }

      return nullptr;

    default:
      return nullptr;
  }
}

Ref<ATNConfig> ParserATNSimulator::actionTransition(Ref<ATNConfig> const& config, const ActionTransition *t) {
#if DFA_DEBUG == 1
    std::cout << "ACTION edge " << t->ruleIndex << ":" << t->actionIndex << std::endl;
#endif

  return std::make_shared<ATNConfig>(*config, t->target);
}

Ref<ATNConfig> ParserATNSimulator::precedenceTransition(Ref<ATNConfig> const& config, const PrecedencePredicateTransition *pt,
    bool collectPredicates, bool inContext, bool fullCtx) {
#if DFA_DEBUG == 1
    std::cout << "PRED (collectPredicates=" << collectPredicates << ") " << pt->getPrecedence() << ">=_p" << ", ctx dependent=true" << std::endl;
    if (parser != nullptr) {
      std::cout << "context surrounding pred is " << Arrays::listToString(parser->getRuleInvocationStack(), ", ") << std::endl;
    }
#endif

  Ref<ATNConfig> c;
  if (collectPredicates && inContext) {
    const auto &predicate = pt->getPredicate();

    if (fullCtx) {
      // In full context mode, we can evaluate predicates on-the-fly
      // during closure, which dramatically reduces the size of
      // the config sets. It also obviates the need to test predicates
      // later during conflict resolution.
      size_t currentPosition = _input->index();
      _input->seek(_startIndex);
      bool predSucceeds = evalSemanticContext(predicate, _outerContext, config->alt, fullCtx);
      _input->seek(currentPosition);
      if (predSucceeds) {
        c = std::make_shared<ATNConfig>(*config, pt->target); // no pred context
      }
    } else {
      Ref<const SemanticContext> newSemCtx = SemanticContext::And(config->semanticContext, predicate);
      c = std::make_shared<ATNConfig>(*config, pt->target, std::move(newSemCtx));
    }
  } else {
    c = std::make_shared<ATNConfig>(*config, pt->target);
  }

#if DFA_DEBUG == 1
    std::cout << "config from pred transition=" << c << std::endl;
#endif

  return c;
}

Ref<ATNConfig> ParserATNSimulator::predTransition(Ref<ATNConfig> const& config, const PredicateTransition *pt,
  bool collectPredicates, bool inContext, bool fullCtx) {
#if DFA_DEBUG == 1
    std::cout << "PRED (collectPredicates=" << collectPredicates << ") " << pt->getRuleIndex() << ":" << pt->getPredIndex() << ", ctx dependent=" << pt->isCtxDependent() << std::endl;
    if (parser != nullptr) {
      std::cout << "context surrounding pred is " << Arrays::listToString(parser->getRuleInvocationStack(), ", ") << std::endl;
    }
#endif

  Ref<ATNConfig> c = nullptr;
  if (collectPredicates && (!pt->isCtxDependent() || (pt->isCtxDependent() && inContext))) {
    const auto &predicate = pt->getPredicate();
    if (fullCtx) {
      // In full context mode, we can evaluate predicates on-the-fly
      // during closure, which dramatically reduces the size of
      // the config sets. It also obviates the need to test predicates
      // later during conflict resolution.
      size_t currentPosition = _input->index();
      _input->seek(_startIndex);
      bool predSucceeds = evalSemanticContext(predicate, _outerContext, config->alt, fullCtx);
      _input->seek(currentPosition);
      if (predSucceeds) {
        c = std::make_shared<ATNConfig>(*config, pt->target); // no pred context
      }
    } else {
      Ref<const SemanticContext> newSemCtx = SemanticContext::And(config->semanticContext, predicate);
      c = std::make_shared<ATNConfig>(*config, pt->target, std::move(newSemCtx));
    }
  } else {
    c = std::make_shared<ATNConfig>(*config, pt->target);
  }

#if DFA_DEBUG == 1
    std::cout << "config from pred transition=" << c << std::endl;
#endif

  return c;
}

Ref<ATNConfig> ParserATNSimulator::ruleTransition(Ref<ATNConfig> const& config, const RuleTransition *t) {
#if DFA_DEBUG == 1
    std::cout << "CALL rule " << getRuleName(t->target->ruleIndex) << ", ctx=" << config->context << std::endl;
#endif

  atn::ATNState *returnState = t->followState;
  Ref<const PredictionContext> newContext = SingletonPredictionContext::create(config->context, returnState->stateNumber);
  return std::make_shared<ATNConfig>(*config, t->target, newContext);
}

BitSet ParserATNSimulator::getConflictingAlts(ATNConfigSet *configs) {
  std::vector<BitSet> altsets = PredictionModeClass::getConflictingAltSubsets(configs);
  return PredictionModeClass::getAlts(altsets);
}

BitSet ParserATNSimulator::getConflictingAltsOrUniqueAlt(ATNConfigSet *configs) {
  BitSet conflictingAlts;
  if (configs->uniqueAlt != ATN::INVALID_ALT_NUMBER) {
    conflictingAlts.set(configs->uniqueAlt);
  } else {
    conflictingAlts = configs->conflictingAlts;
  }
  return conflictingAlts;
}

std::string ParserATNSimulator::getTokenName(size_t t) {
  if (t == Token::EOF) {
    return "EOF";
  }

  const dfa::Vocabulary &vocabulary = parser != nullptr ? parser->getVocabulary() : dfa::Vocabulary();
  std::string displayName = vocabulary.getDisplayName(t);
  if (displayName == std::to_string(t)) {
    return displayName;
  }

  return displayName + "<" + std::to_string(t) + ">";
}

std::string ParserATNSimulator::getLookaheadName(TokenStream *input) {
  return getTokenName(input->LA(1));
}

void ParserATNSimulator::dumpDeadEndConfigs(NoViableAltException &nvae) {
  std::cerr << "dead end configs: ";
  for (const auto &c : nvae.getDeadEndConfigs()->configs) {
    std::string trans = "no edges";
    if (c->state->transitions.size() > 0) {
      const Transition *t = c->state->transitions[0].get();
      if (t != nullptr && t->getTransitionType() == TransitionType::ATOM) {
        const AtomTransition *at = static_cast<const AtomTransition*>(t);
        trans = "Atom " + getTokenName(at->_label);
      } else if (t != nullptr && t->getTransitionType() == TransitionType::SET) {
        const SetTransition *st = static_cast<const SetTransition*>(t);
        trans = "Set ";
        trans += st->set.toString();
      } else if (t != nullptr && t->getTransitionType() == TransitionType::NOT_SET) {
        const SetTransition *st = static_cast<const NotSetTransition*>(t);
        trans = "~Set ";
        trans += st->set.toString();
      }
    }
    std::cerr << c->toString(true) + ":" + trans;
  }
}

NoViableAltException ParserATNSimulator::noViableAlt(TokenStream *input, ParserRuleContext *outerContext,
  ATNConfigSet *configs, size_t startIndex, bool deleteConfigs) {
  return NoViableAltException(parser, input, input->get(startIndex), input->LT(1), configs, outerContext, deleteConfigs);
}

size_t ParserATNSimulator::getUniqueAlt(ATNConfigSet *configs) {
  size_t alt = ATN::INVALID_ALT_NUMBER;
  for (const auto &c : configs->configs) {
    if (alt == ATN::INVALID_ALT_NUMBER) {
      alt = c->alt; // found first alt
    } else if (c->alt != alt) {
      return ATN::INVALID_ALT_NUMBER;
    }
  }
  return alt;
}

dfa::DFAState *ParserATNSimulator::addDFAEdge(dfa::DFA &dfa, dfa::DFAState *from, ssize_t t, dfa::DFAState *to) {
#if DFA_DEBUG == 1
    std::cout << "EDGE " << from << " -> " << to << " upon " << getTokenName(t) << std::endl;
#endif

  if (to == nullptr) {
    return nullptr;
  }

  {
    UniqueLock<SharedMutex> stateLock(atn._stateMutex);
    to = addDFAState(dfa, to); // used existing if possible not incoming
  }
  if (from == nullptr || t > (int)atn.maxTokenType) {
    return to;
  }

  {
    UniqueLock<SharedMutex> edgeLock(atn._edgeMutex);
    from->edges[t] = to; // connect
  }

#if DFA_DEBUG == 1
    std::string dfaText;
    if (parser != nullptr) {
      dfaText = dfa.toString(parser->getVocabulary());
    } else {
      dfaText = dfa.toString(dfa::Vocabulary());
    }
    std::cout << "DFA=\n" << dfaText << std::endl;
#endif

  return to;
}

dfa::DFAState *ParserATNSimulator::addDFAState(dfa::DFA &dfa, dfa::DFAState *D) {
  if (D == ERROR.get()) {
    return D;
  }

  // Optimizing the configs below should not alter the hash code. Thus we can just do an insert
  // which will only succeed if an equivalent DFAState does not already exist.
  auto [existing, inserted] = dfa.states.insert(D);
  if (!inserted) {
#if TRACE_ATN_SIM == 1
      std::cout << "addDFAState " << D->toString() << " exists" << std::endl;
#endif
    return *existing;
  }

  // Previously we did a lookup, then set fields, then inserted. It was `dfa.states.size()`, since
  // we already inserted we need to subtract one.
  D->stateNumber = static_cast<int>(dfa.states.size() - 1);

#if TRACE_ATN_SIM == 1
    std::cout << "addDFAState new " << D->toString() << std::endl;
#endif

  if (!D->configs->isReadonly()) {
    D->configs->optimizeConfigs(this);
    D->configs->setReadonly(true);
  }

#if DFA_DEBUG == 1
  std::cout << "adding new DFA state: " << D << std::endl;
#endif

  return D;
}

void ParserATNSimulator::reportAttemptingFullContext(dfa::DFA &dfa, const antlrcpp::BitSet &conflictingAlts,
  ATNConfigSet *configs, size_t startIndex, size_t stopIndex) {
#if DFA_DEBUG == 1 || RETRY_DEBUG == 1
    misc::Interval interval = misc::Interval((int)startIndex, (int)stopIndex);
    std::cout << "reportAttemptingFullContext decision=" << dfa.decision << ":" << configs << ", input=" << parser->getTokenStream()->getText(interval) << std::endl;
#endif

  if (parser != nullptr) {
    parser->getErrorListenerDispatch().reportAttemptingFullContext(parser, dfa, startIndex, stopIndex, conflictingAlts, configs);
  }
}

void ParserATNSimulator::reportContextSensitivity(dfa::DFA &dfa, size_t prediction, ATNConfigSet *configs,
  size_t startIndex, size_t stopIndex) {
#if DFA_DEBUG == 1 || RETRY_DEBUG == 1
    misc::Interval interval = misc::Interval(startIndex, stopIndex);
    std::cout << "reportContextSensitivity decision=" << dfa.decision << ":" << configs << ", input=" << parser->getTokenStream()->getText(interval) << std::endl;
#endif

  if (parser != nullptr) {
    parser->getErrorListenerDispatch().reportContextSensitivity(parser, dfa, startIndex, stopIndex, prediction, configs);
  }
}

void ParserATNSimulator::reportAmbiguity(dfa::DFA &dfa, dfa::DFAState * /*D*/, size_t startIndex, size_t stopIndex,
                                         bool exact, const antlrcpp::BitSet &ambigAlts, ATNConfigSet *configs) {
#if DFA_DEBUG == 1 || RETRY_DEBUG == 1
    misc::Interval interval = misc::Interval((int)startIndex, (int)stopIndex);
    std::cout << "reportAmbiguity " << ambigAlts << ":" << configs << ", input=" << parser->getTokenStream()->getText(interval) << std::endl;
#endif

  if (parser != nullptr) {
    parser->getErrorListenerDispatch().reportAmbiguity(parser, dfa, startIndex, stopIndex, exact, ambigAlts, configs);
  }
}

void ParserATNSimulator::setPredictionMode(PredictionMode newMode) {
  _mode = newMode;
}

atn::PredictionMode ParserATNSimulator::getPredictionMode() {
  return _mode;
}

Parser* ParserATNSimulator::getParser() {
  return parser;
}

#ifdef _MSC_VER
#pragma warning (disable:4996) // 'getenv': This function or variable may be unsafe. Consider using _dupenv_s instead.
#endif

bool ParserATNSimulator::getLrLoopSetting() {
  char *var = std::getenv("TURN_OFF_LR_LOOP_ENTRY_BRANCH_OPT");
  if (var == nullptr)
    return false;
  std::string value(var);
  return value == "true" || value == "1";
}

#ifdef _MSC_VER
#pragma warning (default:4996)
#endif

void ParserATNSimulator::InitializeInstanceFields() {
  _mode = PredictionMode::LL;
  _startIndex = 0;
}
