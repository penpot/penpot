/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/LexerActionType.h"
#include "atn/LexerAction.h"

namespace antlr4 {
namespace atn {

  /// Implements the {@code type} lexer action by calling <seealso cref="Lexer#setType"/>
  /// with the assigned type.
  class ANTLR4CPP_PUBLIC LexerTypeAction final : public LexerAction {
  public:
    static bool is(const LexerAction &lexerAction) { return lexerAction.getActionType() == LexerActionType::TYPE; }

    static bool is(const LexerAction *lexerAction) { return lexerAction != nullptr && is(*lexerAction); }

    /// <summary>
    /// Constructs a new {@code type} action with the specified token type value. </summary>
    /// <param name="type"> The type to assign to the token using <seealso cref="Lexer#setType"/>. </param>
    explicit LexerTypeAction(int type);

    /// <summary>
    /// Gets the type to assign to a token created by the lexer. </summary>
    /// <returns> The type to assign to a token created by the lexer. </returns>
    int getType() const { return _type; }

    /// <summary>
    /// {@inheritDoc}
    ///
    /// <para>This action is implemented by calling <seealso cref="Lexer#setType"/> with the
    /// value provided by <seealso cref="#getType"/>.</para>
    /// </summary>
    void execute(Lexer *lexer) const override;

    bool equals(const LexerAction &obj) const override;
    std::string toString() const override;

  protected:
    size_t hashCodeImpl() const override;

  private:
    const int _type;
  };

} // namespace atn
} // namespace antlr4
