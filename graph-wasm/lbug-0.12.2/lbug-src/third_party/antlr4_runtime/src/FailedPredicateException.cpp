/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ParserATNSimulator.h"
#include "Parser.h"
#include "atn/PredicateTransition.h"
#include "atn/ATN.h"
#include "atn/ATNState.h"
#include "support/Casts.h"
#include "support/CPPUtils.h"

#include "FailedPredicateException.h"

using namespace antlr4;
using namespace antlrcpp;

FailedPredicateException::FailedPredicateException(Parser *recognizer) : FailedPredicateException(recognizer, "", "") {
}

FailedPredicateException::FailedPredicateException(Parser *recognizer, const std::string &predicate): FailedPredicateException(recognizer, predicate, "") {
}

FailedPredicateException::FailedPredicateException(Parser *recognizer, const std::string &predicate, const std::string &message)
  : RecognitionException(!message.empty() ? message : "failed predicate: " + predicate + "?", recognizer,
                         recognizer->getInputStream(), recognizer->getContext(), recognizer->getCurrentToken()) {

  atn::ATNState *s = recognizer->getInterpreter<atn::ATNSimulator>()->atn.states[recognizer->getState()];
  const atn::Transition *transition = s->transitions[0].get();
  if (transition->getTransitionType() == atn::TransitionType::PREDICATE) {
    _ruleIndex = downCast<const atn::PredicateTransition&>(*transition).getRuleIndex();
    _predicateIndex = downCast<const atn::PredicateTransition&>(*transition).getPredIndex();
  } else {
    _ruleIndex = 0;
    _predicateIndex = 0;
  }

  _predicate = predicate;
}

size_t FailedPredicateException::getRuleIndex() {
  return _ruleIndex;
}

size_t FailedPredicateException::getPredIndex() {
  return _predicateIndex;
}

std::string FailedPredicateException::getPredicate() {
  return _predicate;
}
