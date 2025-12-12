/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "Token.h"
#include "atn/ATNConfig.h"
#include "atn/PredictionContext.h"
#include "support/BitSet.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC LL1Analyzer final {
  public:
    /// Special value added to the lookahead sets to indicate that we hit
    ///  a predicate during analysis if {@code seeThruPreds==false}.
    static constexpr size_t HIT_PRED = Token::INVALID_TYPE;

    explicit LL1Analyzer(const atn::ATN &atn) : _atn(atn) {}

    /// <summary>
    /// Calculates the SLL(1) expected lookahead set for each outgoing transition
    /// of an <seealso cref="ATNState"/>. The returned array has one element for each
    /// outgoing transition in {@code s}. If the closure from transition
    /// <em>i</em> leads to a semantic predicate before matching a symbol, the
    /// element at index <em>i</em> of the result will be {@code null}.
    /// </summary>
    /// <param name="s"> the ATN state </param>
    /// <returns> the expected symbols for each outgoing transition of {@code s}. </returns>
    std::vector<misc::IntervalSet> getDecisionLookahead(ATNState *s) const;

    /// <summary>
    /// Compute set of tokens that can follow {@code s} in the ATN in the
    /// specified {@code ctx}.
    /// <p/>
    /// If {@code ctx} is {@code null} and the end of the rule containing
    /// {@code s} is reached, <seealso cref="Token#EPSILON"/> is added to the result set.
    /// If {@code ctx} is not {@code null} and the end of the outermost rule is
    /// reached, <seealso cref="Token#EOF"/> is added to the result set.
    /// </summary>
    /// <param name="s"> the ATN state </param>
    /// <param name="ctx"> the complete parser context, or {@code null} if the context
    /// should be ignored
    /// </param>
    /// <returns> The set of tokens that can follow {@code s} in the ATN in the
    /// specified {@code ctx}. </returns>
    misc::IntervalSet LOOK(ATNState *s, RuleContext *ctx) const;

    /// <summary>
    /// Compute set of tokens that can follow {@code s} in the ATN in the
    /// specified {@code ctx}.
    /// <p/>
    /// If {@code ctx} is {@code null} and the end of the rule containing
    /// {@code s} is reached, <seealso cref="Token#EPSILON"/> is added to the result set.
    /// If {@code ctx} is not {@code null} and the end of the outermost rule is
    /// reached, <seealso cref="Token#EOF"/> is added to the result set.
    /// </summary>
    /// <param name="s"> the ATN state </param>
    /// <param name="stopState"> the ATN state to stop at. This can be a
    /// <seealso cref="BlockEndState"/> to detect epsilon paths through a closure. </param>
    /// <param name="ctx"> the complete parser context, or {@code null} if the context
    /// should be ignored
    /// </param>
    /// <returns> The set of tokens that can follow {@code s} in the ATN in the
    /// specified {@code ctx}. </returns>
    misc::IntervalSet LOOK(ATNState *s, ATNState *stopState, RuleContext *ctx) const;

  private:
    const atn::ATN &_atn;
  };

} // namespace atn
} // namespace antlr4
