/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/Transition.h"
#include "atn/SemanticContext.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC PrecedencePredicateTransition final : public Transition {
  public:
    static bool is(const Transition &transition) { return transition.getTransitionType() == TransitionType::PRECEDENCE; }

    static bool is(const Transition *transition) { return transition != nullptr && is(*transition); }

    PrecedencePredicateTransition(ATNState *target, int precedence);

    int getPrecedence() const { return _predicate->precedence; }

    bool isEpsilon() const override;
    bool matches(size_t symbol, size_t minVocabSymbol, size_t maxVocabSymbol) const override;
    std::string toString() const override;

    const Ref<const SemanticContext::PrecedencePredicate>& getPredicate() const { return _predicate; }

  private:
    const std::shared_ptr<const SemanticContext::PrecedencePredicate> _predicate;
  };

} // namespace atn
} // namespace antlr4
