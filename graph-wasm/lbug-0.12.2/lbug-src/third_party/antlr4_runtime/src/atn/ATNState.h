/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "misc/IntervalSet.h"
#include "atn/Transition.h"
#include "atn/ATNStateType.h"

namespace antlr4 {
namespace atn {

  /// <summary>
  /// The following images show the relation of states and
  /// <seealso cref="ATNState#transitions"/> for various grammar constructs.
  ///
  /// <ul>
  ///
  /// <li>Solid edges marked with an &#0949; indicate a required
  /// <seealso cref="EpsilonTransition"/>.</li>
  ///
  /// <li>Dashed edges indicate locations where any transition derived from
  /// <seealso cref="Transition"/> might appear.</li>
  ///
  /// <li>Dashed nodes are place holders for either a sequence of linked
  /// <seealso cref="BasicState"/> states or the inclusion of a block representing a nested
  /// construct in one of the forms below.</li>
  ///
  /// <li>Nodes showing multiple outgoing alternatives with a {@code ...} support
  /// any number of alternatives (one or more). Nodes without the {@code ...} only
  /// support the exact number of alternatives shown in the diagram.</li>
  ///
  /// </ul>
  ///
  /// <h2>Basic Blocks</h2>
  ///
  /// <h3>Rule</h3>
  ///
  /// <embed src="images/Rule.svg" type="image/svg+xml"/>
  ///
  /// <h3>Block of 1 or more alternatives</h3>
  ///
  /// <embed src="images/Block.svg" type="image/svg+xml"/>
  ///
  /// <h2>Greedy Loops</h2>
  ///
  /// <h3>Greedy Closure: {@code (...)*}</h3>
  ///
  /// <embed src="images/ClosureGreedy.svg" type="image/svg+xml"/>
  ///
  /// <h3>Greedy Positive Closure: {@code (...)+}</h3>
  ///
  /// <embed src="images/PositiveClosureGreedy.svg" type="image/svg+xml"/>
  ///
  /// <h3>Greedy Optional: {@code (...)?}</h3>
  ///
  /// <embed src="images/OptionalGreedy.svg" type="image/svg+xml"/>
  ///
  /// <h2>Non-Greedy Loops</h2>
  ///
  /// <h3>Non-Greedy Closure: {@code (...)*?}</h3>
  ///
  /// <embed src="images/ClosureNonGreedy.svg" type="image/svg+xml"/>
  ///
  /// <h3>Non-Greedy Positive Closure: {@code (...)+?}</h3>
  ///
  /// <embed src="images/PositiveClosureNonGreedy.svg" type="image/svg+xml"/>
  ///
  /// <h3>Non-Greedy Optional: {@code (...)??}</h3>
  ///
  /// <embed src="images/OptionalNonGreedy.svg" type="image/svg+xml"/>
  /// </summary>

// GCC generates a warning here if ATN has already been declared due to the
// attributes added by ANTLR4CPP_PUBLIC.
// See: https://gcc.gnu.org/bugzilla/show_bug.cgi?id=39159
// Only forward-declare if it hasn't already been declared.
#ifndef ANTLR4CPP_ATN_DECLARED
  class ANTLR4CPP_PUBLIC ATN;
#endif

  class ANTLR4CPP_PUBLIC ATNState {
  public:
    static constexpr size_t INITIAL_NUM_TRANSITIONS = 4;
    static constexpr size_t INVALID_STATE_NUMBER = std::numeric_limits<size_t>::max();

    size_t stateNumber = INVALID_STATE_NUMBER;
    size_t ruleIndex = 0; // at runtime, we don't have Rule objects
    bool epsilonOnlyTransitions = false;

    /// Track the transitions emanating from this ATN state.
    std::vector<ConstTransitionPtr> transitions;

    ATNState() = delete;

    ATNState(ATNState const&) = delete;

    ATNState(ATNState&&) = delete;

    virtual ~ATNState() = default;

    ATNState& operator=(ATNState const&) = delete;

    ATNState& operator=(ATNState&&) = delete;

    void addTransition(ConstTransitionPtr e);
    void addTransition(size_t index, ConstTransitionPtr e);
    ConstTransitionPtr removeTransition(size_t index);

    virtual size_t hashCode() const;
    virtual bool equals(const ATNState &other) const;

    virtual bool isNonGreedyExitState() const;
    virtual std::string toString() const;

    ATNStateType getStateType() const { return _stateType; }

  protected:
    explicit ATNState(ATNStateType stateType) : _stateType(stateType) {}

  private:
    /// Used to cache lookahead during parsing, not used during construction.

    misc::IntervalSet _nextTokenWithinRule;
    std::atomic<bool> _nextTokenUpdated { false };

    const ATNStateType _stateType;

    friend class ATN;
  };

  inline bool operator==(const ATNState &lhs, const ATNState &rhs) { return lhs.equals(rhs); }

  inline bool operator!=(const ATNState &lhs, const ATNState &rhs) { return !operator==(lhs, rhs); }

} // namespace atn
} // namespace antlr4
