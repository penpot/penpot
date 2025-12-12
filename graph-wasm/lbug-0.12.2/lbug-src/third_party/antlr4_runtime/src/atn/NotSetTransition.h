/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/SetTransition.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC NotSetTransition final : public SetTransition {
  public:
    static bool is(const Transition &transition) { return transition.getTransitionType() == TransitionType::NOT_SET; }

    static bool is(const Transition *transition) { return transition != nullptr && is(*transition); }

    NotSetTransition(ATNState *target, misc::IntervalSet set);

    virtual bool matches(size_t symbol, size_t minVocabSymbol, size_t maxVocabSymbol) const override;

    virtual std::string toString() const override;
  };

} // namespace atn
} // namespace antlr4
