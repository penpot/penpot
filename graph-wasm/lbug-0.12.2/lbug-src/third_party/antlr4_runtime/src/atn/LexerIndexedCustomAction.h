/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "RuleContext.h"
#include "atn/LexerAction.h"

namespace antlr4 {
namespace atn {

  /// <summary>
  /// This implementation of <seealso cref="LexerAction"/> is used for tracking input offsets
  /// for position-dependent actions within a <seealso cref="LexerActionExecutor"/>.
  ///
  /// <para>This action is not serialized as part of the ATN, and is only required for
  /// position-dependent lexer actions which appear at a location other than the
  /// end of a rule. For more information about DFA optimizations employed for
  /// lexer actions, see <seealso cref="LexerActionExecutor#append"/> and
  /// <seealso cref="LexerActionExecutor#fixOffsetBeforeMatch"/>.</para>
  ///
  /// @author Sam Harwell
  /// @since 4.2
  /// </summary>
  class ANTLR4CPP_PUBLIC LexerIndexedCustomAction final : public LexerAction {
  public:
    static bool is(const LexerAction &lexerAction) { return lexerAction.getActionType() == LexerActionType::INDEXED_CUSTOM; }

    static bool is(const LexerAction *lexerAction) { return lexerAction != nullptr && is(*lexerAction); }

    /// <summary>
    /// Constructs a new indexed custom action by associating a character offset
    /// with a <seealso cref="LexerAction"/>.
    ///
    /// <para>Note: This class is only required for lexer actions for which
    /// <seealso cref="LexerAction#isPositionDependent"/> returns {@code true}.</para>
    /// </summary>
    /// <param name="offset"> The offset into the input <seealso cref="CharStream"/>, relative to
    /// the token start index, at which the specified lexer action should be
    /// executed. </param>
    /// <param name="action"> The lexer action to execute at a particular offset in the
    /// input <seealso cref="CharStream"/>. </param>
    LexerIndexedCustomAction(int offset, Ref<const LexerAction> action);

    /// <summary>
    /// Gets the location in the input <seealso cref="CharStream"/> at which the lexer
    /// action should be executed. The value is interpreted as an offset relative
    /// to the token start index.
    /// </summary>
    /// <returns> The location in the input <seealso cref="CharStream"/> at which the lexer
    /// action should be executed. </returns>
    int getOffset() const { return _offset; }

    /// <summary>
    /// Gets the lexer action to execute.
    /// </summary>
    /// <returns> A <seealso cref="LexerAction"/> object which executes the lexer action. </returns>
    const Ref<const LexerAction>& getAction() const { return _action; }

    void execute(Lexer *lexer) const override;
    bool equals(const LexerAction &other) const override;
    std::string toString() const override;

  protected:
    size_t hashCodeImpl() const override;

  private:
    const Ref<const LexerAction> _action;
    const int _offset;
  };

} // namespace atn
} // namespace antlr4

