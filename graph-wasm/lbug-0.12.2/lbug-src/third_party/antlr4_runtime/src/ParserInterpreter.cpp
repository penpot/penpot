/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "dfa/DFA.h"
#include "atn/RuleStartState.h"
#include "InterpreterRuleContext.h"
#include "atn/ParserATNSimulator.h"
#include "ANTLRErrorStrategy.h"
#include "atn/LoopEndState.h"
#include "FailedPredicateException.h"
#include "atn/StarLoopEntryState.h"
#include "atn/AtomTransition.h"
#include "atn/RuleTransition.h"
#include "atn/PredicateTransition.h"
#include "atn/PrecedencePredicateTransition.h"
#include "atn/ActionTransition.h"
#include "atn/ATN.h"
#include "atn/RuleStopState.h"
#include "Lexer.h"
#include "Token.h"
#include "Vocabulary.h"
#include "InputMismatchException.h"
#include "CommonToken.h"
#include "tree/ErrorNode.h"

#include "support/CPPUtils.h"
#include "support/Casts.h"

#include "ParserInterpreter.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;

using namespace antlrcpp;

ParserInterpreter::ParserInterpreter(const std::string &grammarFileName, const dfa::Vocabulary &vocabulary,
  const std::vector<std::string> &ruleNames, const atn::ATN &atn, TokenStream *input)
  : Parser(input), _grammarFileName(grammarFileName), _atn(atn), _ruleNames(ruleNames), _vocabulary(vocabulary) {

  // init decision DFA
  for (size_t i = 0; i < atn.getNumberOfDecisions(); ++i) {
    atn::DecisionState *decisionState = atn.getDecisionState(i);
    _decisionToDFA.push_back(dfa::DFA(decisionState, i));
  }

  // get atn simulator that knows how to do predictions
  _interpreter = new atn::ParserATNSimulator(this, atn, _decisionToDFA, _sharedContextCache); /* mem-check: deleted in d-tor */
}

ParserInterpreter::~ParserInterpreter() {
  delete _interpreter;
}

void ParserInterpreter::reset() {
  Parser::reset();
  _overrideDecisionReached = false;
  _overrideDecisionRoot = nullptr;
}

const atn::ATN& ParserInterpreter::getATN() const {
  return _atn;
}

const dfa::Vocabulary& ParserInterpreter::getVocabulary() const {
  return _vocabulary;
}

const std::vector<std::string>& ParserInterpreter::getRuleNames() const {
  return _ruleNames;
}

std::string ParserInterpreter::getGrammarFileName() const {
  return _grammarFileName;
}

ParserRuleContext* ParserInterpreter::parse(size_t startRuleIndex) {
  atn::RuleStartState *startRuleStartState = _atn.ruleToStartState[startRuleIndex];

  _rootContext = createInterpreterRuleContext(nullptr, atn::ATNState::INVALID_STATE_NUMBER, startRuleIndex);

  if (startRuleStartState->isLeftRecursiveRule) {
    enterRecursionRule(_rootContext, startRuleStartState->stateNumber, startRuleIndex, 0);
  } else {
    enterRule(_rootContext, startRuleStartState->stateNumber, startRuleIndex);
  }

  while (true) {
    atn::ATNState *p = getATNState();
    switch (p->getStateType()) {
      case atn::ATNStateType::RULE_STOP :
        // pop; return from rule
        if (_ctx->isEmpty()) {
          if (startRuleStartState->isLeftRecursiveRule) {
            ParserRuleContext *result = _ctx;
            auto parentContext = _parentContextStack.top();
            _parentContextStack.pop();
            unrollRecursionContexts(parentContext.first);
            return result;
          } else {
            exitRule();
            return _rootContext;
          }
        }

        visitRuleStopState(p);
        break;

      default :
        try {
          visitState(p);
        }
        catch (RecognitionException &e) {
          setState(_atn.ruleToStopState[p->ruleIndex]->stateNumber);
          getErrorHandler()->reportError(this, e);
          getContext()->exception = std::current_exception();
          recover(e);
        }

        break;
    }
  }
}

void ParserInterpreter::enterRecursionRule(ParserRuleContext *localctx, size_t state, size_t ruleIndex, int precedence) {
  _parentContextStack.push({ _ctx, localctx->invokingState });
  Parser::enterRecursionRule(localctx, state, ruleIndex, precedence);
}

void ParserInterpreter::addDecisionOverride(int decision, int tokenIndex, int forcedAlt) {
  _overrideDecision = decision;
  _overrideDecisionInputIndex = tokenIndex;
  _overrideDecisionAlt = forcedAlt;
}

Ref<InterpreterRuleContext> ParserInterpreter::getOverrideDecisionRoot() const {
  return _overrideDecisionRoot;
}

InterpreterRuleContext* ParserInterpreter::getRootContext() {
  return _rootContext;
}

atn::ATNState* ParserInterpreter::getATNState() {
  return _atn.states[getState()];
}

void ParserInterpreter::visitState(atn::ATNState *p) {
  size_t predictedAlt = 1;
  if (DecisionState::is(p)) {
    predictedAlt = visitDecisionState(downCast<DecisionState*>(p));
  }

  const atn::Transition *transition = p->transitions[predictedAlt - 1].get();
  switch (transition->getTransitionType()) {
    case atn::TransitionType::EPSILON:
      if (p->getStateType() == ATNStateType::STAR_LOOP_ENTRY &&
        (downCast<StarLoopEntryState *>(p))->isPrecedenceDecision &&
        !LoopEndState::is(transition->target)) {
        // We are at the start of a left recursive rule's (...)* loop
        // and we're not taking the exit branch of loop.
        InterpreterRuleContext *localctx = createInterpreterRuleContext(_parentContextStack.top().first,
          _parentContextStack.top().second, static_cast<int>(_ctx->getRuleIndex()));
        pushNewRecursionContext(localctx, _atn.ruleToStartState[p->ruleIndex]->stateNumber, static_cast<int>(_ctx->getRuleIndex()));
      }
      break;

    case atn::TransitionType::ATOM:
      match(static_cast<int>(static_cast<const atn::AtomTransition*>(transition)->_label));
      break;

    case atn::TransitionType::RANGE:
    case atn::TransitionType::SET:
    case atn::TransitionType::NOT_SET:
      if (!transition->matches(static_cast<int>(_input->LA(1)), Token::MIN_USER_TOKEN_TYPE, Lexer::MAX_CHAR_VALUE)) {
        recoverInline();
      }
      matchWildcard();
      break;

    case atn::TransitionType::WILDCARD:
      matchWildcard();
      break;

    case atn::TransitionType::RULE:
    {
      atn::RuleStartState *ruleStartState = static_cast<atn::RuleStartState*>(transition->target);
      size_t ruleIndex = ruleStartState->ruleIndex;
      InterpreterRuleContext *newctx = createInterpreterRuleContext(_ctx, p->stateNumber, ruleIndex);
      if (ruleStartState->isLeftRecursiveRule) {
        enterRecursionRule(newctx, ruleStartState->stateNumber, ruleIndex, static_cast<const atn::RuleTransition*>(transition)->precedence);
      } else {
        enterRule(newctx, transition->target->stateNumber, ruleIndex);
      }
    }
      break;

    case atn::TransitionType::PREDICATE:
    {
      const atn::PredicateTransition *predicateTransition = static_cast<const atn::PredicateTransition*>(transition);
      if (!sempred(_ctx, predicateTransition->getRuleIndex(), predicateTransition->getPredIndex())) {
        throw FailedPredicateException(this);
      }
    }
      break;

    case atn::TransitionType::ACTION:
    {
      const atn::ActionTransition *actionTransition = static_cast<const atn::ActionTransition*>(transition);
      action(_ctx, actionTransition->ruleIndex, actionTransition->actionIndex);
    }
      break;

    case atn::TransitionType::PRECEDENCE:
    {
      if (!precpred(_ctx, static_cast<const atn::PrecedencePredicateTransition*>(transition)->getPrecedence())) {
        throw FailedPredicateException(this, "precpred(_ctx, " + std::to_string(static_cast<const atn::PrecedencePredicateTransition*>(transition)->getPrecedence()) +  ")");
      }
    }
      break;

    default:
      throw UnsupportedOperationException("Unrecognized ATN transition type.");
  }

  setState(transition->target->stateNumber);
}

size_t ParserInterpreter::visitDecisionState(DecisionState *p) {
  size_t predictedAlt = 1;
  if (p->transitions.size() > 1) {
    getErrorHandler()->sync(this);
    int decision = p->decision;
    if (decision == _overrideDecision && _input->index() == _overrideDecisionInputIndex && !_overrideDecisionReached) {
      predictedAlt = _overrideDecisionAlt;
      _overrideDecisionReached = true;
    } else {
      predictedAlt = getInterpreter<ParserATNSimulator>()->adaptivePredict(_input, decision, _ctx);
    }
  }
  return predictedAlt;
}

InterpreterRuleContext* ParserInterpreter::createInterpreterRuleContext(ParserRuleContext *parent,
  size_t invokingStateNumber, size_t ruleIndex) {
  return _tracker.createInstance<InterpreterRuleContext>(parent, invokingStateNumber, ruleIndex);
}

void ParserInterpreter::visitRuleStopState(atn::ATNState *p) {
  atn::RuleStartState *ruleStartState = _atn.ruleToStartState[p->ruleIndex];
  if (ruleStartState->isLeftRecursiveRule) {
    std::pair<ParserRuleContext *, size_t> parentContext = _parentContextStack.top();
    _parentContextStack.pop();

    unrollRecursionContexts(parentContext.first);
    setState(parentContext.second);
  } else {
    exitRule();
  }

  const atn::RuleTransition *ruleTransition = static_cast<const atn::RuleTransition*>(_atn.states[getState()]->transitions[0].get());
  setState(ruleTransition->followState->stateNumber);
}

void ParserInterpreter::recover(RecognitionException &e) {
  size_t i = _input->index();
  getErrorHandler()->recover(this, std::make_exception_ptr(e));

  if (_input->index() == i) {
    // no input consumed, better add an error node
    if (is<InputMismatchException *>(&e)) {
      InputMismatchException &ime = static_cast<InputMismatchException&>(e);
      Token *tok = e.getOffendingToken();
      size_t expectedTokenType = ime.getExpectedTokens().getMinElement(); // get any element
      _errorToken = getTokenFactory()->create({ tok->getTokenSource(), tok->getTokenSource()->getInputStream() },
        expectedTokenType, tok->getText(), Token::DEFAULT_CHANNEL, INVALID_INDEX, INVALID_INDEX, // invalid start/stop
        tok->getLine(), tok->getCharPositionInLine());
      _ctx->addChild(createErrorNode(_errorToken.get()));
    }
    else { // NoViableAlt
      Token *tok = e.getOffendingToken();
      _errorToken = getTokenFactory()->create({ tok->getTokenSource(), tok->getTokenSource()->getInputStream() },
        Token::INVALID_TYPE, tok->getText(), Token::DEFAULT_CHANNEL, INVALID_INDEX, INVALID_INDEX, // invalid start/stop
        tok->getLine(), tok->getCharPositionInLine());
      _ctx->addChild(createErrorNode(_errorToken.get()));
    }
  }
}

Token* ParserInterpreter::recoverInline() {
  return _errHandler->recoverInline(this);
}
