/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/Transition.h"
#include "atn/SemanticContext.h"

namespace antlr4 {
namespace atn {

  /// TODO: this is old comment:
  ///  A tree of semantic predicates from the grammar AST if label==SEMPRED.
  ///  In the ATN, labels will always be exactly one predicate, but the DFA
  ///  may have to combine a bunch of them as it collects predicates from
  ///  multiple ATN configurations into a single DFA state.
  class ANTLR4CPP_PUBLIC PredicateTransition final : public Transition {
  public:
    static bool is(const Transition &transition) { return transition.getTransitionType() == TransitionType::PREDICATE; }

    static bool is(const Transition *transition) { return transition != nullptr && is(*transition); }

    PredicateTransition(ATNState *target, size_t ruleIndex, size_t predIndex, bool isCtxDependent);

    size_t getRuleIndex() const {
      return _predicate->ruleIndex;
    }

    size_t getPredIndex() const {
      return _predicate->predIndex;
    }

    bool isCtxDependent() const {
      return _predicate->isCtxDependent;
    }

    bool isEpsilon() const override;
    bool matches(size_t symbol, size_t minVocabSymbol, size_t maxVocabSymbol) const override;
    std::string toString() const override;

    const Ref<const SemanticContext::Predicate>& getPredicate() const { return _predicate; }

  private:
    const std::shared_ptr<const SemanticContext::Predicate> _predicate;
  };

} // namespace atn
} // namespace antlr4
