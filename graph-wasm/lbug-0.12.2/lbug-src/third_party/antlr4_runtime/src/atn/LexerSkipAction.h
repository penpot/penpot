/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/LexerAction.h"
#include "atn/LexerActionType.h"

namespace antlr4 {
namespace atn {

  /// <summary>
  /// Implements the {@code skip} lexer action by calling <seealso cref="Lexer#skip"/>.
  ///
  /// <para>The {@code skip} command does not have any parameters, so this action is
  /// implemented as a singleton instance exposed by <seealso cref="#INSTANCE"/>.</para>
  ///
  /// @author Sam Harwell
  /// @since 4.2
  /// </summary>
  class ANTLR4CPP_PUBLIC LexerSkipAction final : public LexerAction {
  public:
    static bool is(const LexerAction &lexerAction) { return lexerAction.getActionType() == LexerActionType::SKIP; }

    static bool is(const LexerAction *lexerAction) { return lexerAction != nullptr && is(*lexerAction); }

    /// Provides a singleton instance of this parameterless lexer action.
    static const Ref<const LexerSkipAction>& getInstance();

    /// <summary>
    /// {@inheritDoc}
    ///
    /// <para>This action is implemented by calling <seealso cref="Lexer#skip"/>.</para>
    /// </summary>
    void execute(Lexer *lexer) const override;

    bool equals(const LexerAction &obj) const override;
    std::string toString() const override;

  protected:
    size_t hashCodeImpl() const override;

  private:
    /// Constructs the singleton instance of the lexer {@code skip} command.
    LexerSkipAction() : LexerAction(LexerActionType::SKIP, false) {}
  };

} // namespace atn
} // namespace antlr4
