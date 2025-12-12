/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "antlr4-common.h"

namespace antlr4 {
namespace dfa {

  /// This class provides a default implementation of the <seealso cref="Vocabulary"/>
  /// interface.
  class ANTLR4CPP_PUBLIC Vocabulary final {
  public:
    /// Gets an empty <seealso cref="Vocabulary"/> instance.
    ///
    /// <para>
    /// No literal or symbol names are assigned to token types, so
    /// <seealso cref="#getDisplayName(int)"/> returns the numeric value for all tokens
    /// except <seealso cref="Token#EOF"/>.</para>
    [[deprecated("Use the default constructor of Vocabulary instead.")]] static const Vocabulary EMPTY_VOCABULARY;

    Vocabulary() {}

    Vocabulary(const Vocabulary&) = default;

    /// <summary>
    /// Constructs a new instance of <seealso cref="Vocabulary"/> from the specified
    /// literal and symbolic token names.
    /// </summary>
    /// <param name="literalNames"> The literal names assigned to tokens, or {@code null}
    /// if no literal names are assigned. </param>
    /// <param name="symbolicNames"> The symbolic names assigned to tokens, or
    /// {@code null} if no symbolic names are assigned.
    /// </param>
    /// <seealso cref= #getLiteralName(int) </seealso>
    /// <seealso cref= #getSymbolicName(int) </seealso>
    Vocabulary(std::vector<std::string> literalNames, std::vector<std::string> symbolicNames);

    /// <summary>
    /// Constructs a new instance of <seealso cref="Vocabulary"/> from the specified
    /// literal, symbolic, and display token names.
    /// </summary>
    /// <param name="literalNames"> The literal names assigned to tokens, or {@code null}
    /// if no literal names are assigned. </param>
    /// <param name="symbolicNames"> The symbolic names assigned to tokens, or
    /// {@code null} if no symbolic names are assigned. </param>
    /// <param name="displayNames"> The display names assigned to tokens, or {@code null}
    /// to use the values in {@code literalNames} and {@code symbolicNames} as
    /// the source of display names, as described in
    /// <seealso cref="#getDisplayName(int)"/>.
    /// </param>
    /// <seealso cref= #getLiteralName(int) </seealso>
    /// <seealso cref= #getSymbolicName(int) </seealso>
    /// <seealso cref= #getDisplayName(int) </seealso>
    Vocabulary(std::vector<std::string> literalNames, std::vector<std::string> symbolicNames,
               std::vector<std::string> displayNames);

    /// <summary>
    /// Returns the highest token type value. It can be used to iterate from
    /// zero to that number, inclusively, thus querying all stored entries. </summary>
    /// <returns> the highest token type value </returns>
    constexpr size_t getMaxTokenType() const { return _maxTokenType; }

    /// <summary>
    /// Gets the string literal associated with a token type. The string returned
    /// by this method, when not {@code null}, can be used unaltered in a parser
    /// grammar to represent this token type.
    ///
    /// <para>The following table shows examples of lexer rules and the literal
    /// names assigned to the corresponding token types.</para>
    ///
    /// <table>
    ///  <tr>
    ///   <th>Rule</th>
    ///   <th>Literal Name</th>
    ///   <th>Java String Literal</th>
    ///  </tr>
    ///  <tr>
    ///   <td>{@code THIS : 'this';}</td>
    ///   <td>{@code 'this'}</td>
    ///   <td>{@code "'this'"}</td>
    ///  </tr>
    ///  <tr>
    ///   <td>{@code SQUOTE : '\'';}</td>
    ///   <td>{@code '\''}</td>
    ///   <td>{@code "'\\''"}</td>
    ///  </tr>
    ///  <tr>
    ///   <td>{@code ID : [A-Z]+;}</td>
    ///   <td>n/a</td>
    ///   <td>{@code null}</td>
    ///  </tr>
    /// </table>
    /// </summary>
    /// <param name="tokenType"> The token type.
    /// </param>
    /// <returns> The string literal associated with the specified token type, or
    /// {@code null} if no string literal is associated with the type. </returns>
    std::string_view getLiteralName(size_t tokenType) const;

    /// <summary>
    /// Gets the symbolic name associated with a token type. The string returned
    /// by this method, when not {@code null}, can be used unaltered in a parser
    /// grammar to represent this token type.
    ///
    /// <para>This method supports token types defined by any of the following
    /// methods:</para>
    ///
    /// <ul>
    ///  <li>Tokens created by lexer rules.</li>
    ///  <li>Tokens defined in a <code>tokens{}</code> block in a lexer or parser
    ///  grammar.</li>
    ///  <li>The implicitly defined {@code EOF} token, which has the token type
    ///  <seealso cref="Token#EOF"/>.</li>
    /// </ul>
    ///
    /// <para>The following table shows examples of lexer rules and the literal
    /// names assigned to the corresponding token types.</para>
    ///
    /// <table>
    ///  <tr>
    ///   <th>Rule</th>
    ///   <th>Symbolic Name</th>
    ///  </tr>
    ///  <tr>
    ///   <td>{@code THIS : 'this';}</td>
    ///   <td>{@code THIS}</td>
    ///  </tr>
    ///  <tr>
    ///   <td>{@code SQUOTE : '\'';}</td>
    ///   <td>{@code SQUOTE}</td>
    ///  </tr>
    ///  <tr>
    ///   <td>{@code ID : [A-Z]+;}</td>
    ///   <td>{@code ID}</td>
    ///  </tr>
    /// </table>
    /// </summary>
    /// <param name="tokenType"> The token type.
    /// </param>
    /// <returns> The symbolic name associated with the specified token type, or
    /// {@code null} if no symbolic name is associated with the type. </returns>
    std::string_view getSymbolicName(size_t tokenType) const;

    /// <summary>
    /// Gets the display name of a token type.
    ///
    /// <para>ANTLR provides a default implementation of this method, but
    /// applications are free to override the behavior in any manner which makes
    /// sense for the application. The default implementation returns the first
    /// result from the following list which produces a non-{@code null}
    /// result.</para>
    ///
    /// <ol>
    ///  <li>The result of <seealso cref="#getLiteralName"/></li>
    ///  <li>The result of <seealso cref="#getSymbolicName"/></li>
    ///  <li>The result of <seealso cref="Integer#toString"/></li>
    /// </ol>
    /// </summary>
    /// <param name="tokenType"> The token type.
    /// </param>
    /// <returns> The display name of the token type, for use in error reporting or
    /// other user-visible messages which reference specific token types. </returns>
    std::string getDisplayName(size_t tokenType) const;

  private:
    std::vector<std::string> const _literalNames;
    std::vector<std::string> const _symbolicNames;
    std::vector<std::string> const _displayNames;
    const size_t _maxTokenType = 0;
  };

} // namespace atn
} // namespace antlr4
