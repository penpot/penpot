/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/Transition.h"

namespace antlr4 {
namespace atn {

  /// <summary>
  /// A transition containing a set of values. </summary>
  class ANTLR4CPP_PUBLIC SetTransition : public Transition {
  public:
    static bool is(const Transition &transition) {
      const auto transitionType = transition.getTransitionType();
      return transitionType == TransitionType::SET || transitionType == TransitionType::NOT_SET;
    }

    static bool is(const Transition *transition) { return transition != nullptr && is(*transition); }

    const misc::IntervalSet set;

    SetTransition(ATNState *target, misc::IntervalSet set) : SetTransition(TransitionType::SET, target, std::move(set)) {}

    virtual misc::IntervalSet label() const override;
    virtual bool matches(size_t symbol, size_t minVocabSymbol, size_t maxVocabSymbol) const override;

    virtual std::string toString() const override;

  protected:
    SetTransition(TransitionType transitionType, ATNState *target, misc::IntervalSet set);
  };

} // namespace atn
} // namespace antlr4
