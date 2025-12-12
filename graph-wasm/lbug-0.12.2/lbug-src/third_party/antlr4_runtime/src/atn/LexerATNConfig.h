/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/ATNConfig.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC LexerATNConfig final : public ATNConfig {
  public:
    LexerATNConfig(ATNState *state, int alt, Ref<const PredictionContext> context);
    LexerATNConfig(ATNState *state, int alt, Ref<const PredictionContext> context, Ref<const LexerActionExecutor> lexerActionExecutor);

    LexerATNConfig(LexerATNConfig const& other, ATNState *state);
    LexerATNConfig(LexerATNConfig const& other, ATNState *state, Ref<const LexerActionExecutor> lexerActionExecutor);
    LexerATNConfig(LexerATNConfig const& other, ATNState *state, Ref<const PredictionContext> context);

    /**
     * Gets the {@link LexerActionExecutor} capable of executing the embedded
     * action(s) for the current configuration.
     */
    const Ref<const LexerActionExecutor>& getLexerActionExecutor() const { return _lexerActionExecutor; }
    bool hasPassedThroughNonGreedyDecision() const { return _passedThroughNonGreedyDecision; }

    virtual size_t hashCode() const override;

    bool operator==(const LexerATNConfig& other) const;

  private:
    /**
     * This is the backing field for {@link #getLexerActionExecutor}.
     */
    const Ref<const LexerActionExecutor> _lexerActionExecutor;
    const bool _passedThroughNonGreedyDecision = false;

    static bool checkNonGreedyDecision(LexerATNConfig const& source, ATNState *target);
  };

} // namespace atn
} // namespace antlr4
