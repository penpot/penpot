/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/PredicateTransition.h"

using namespace antlr4::atn;

PredicateTransition::PredicateTransition(ATNState *target, size_t ruleIndex, size_t predIndex, bool isCtxDependent)
    : Transition(TransitionType::PREDICATE, target), _predicate(std::make_shared<SemanticContext::Predicate>(ruleIndex, predIndex, isCtxDependent)) {}

bool PredicateTransition::isEpsilon() const {
  return true;
}

bool PredicateTransition::matches(size_t /*symbol*/, size_t /*minVocabSymbol*/, size_t /*maxVocabSymbol*/) const {
  return false;
}

std::string PredicateTransition::toString() const {
  return "PREDICATE " + Transition::toString() + " { ruleIndex: " + std::to_string(getRuleIndex()) +
    ", predIndex: " + std::to_string(getPredIndex()) + ", isCtxDependent: " + std::to_string(isCtxDependent()) + " }";
}
