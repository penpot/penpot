/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "tree/ErrorNode.h"
#include "tree/TerminalNodeImpl.h"
#include "misc/Interval.h"

#include "support/Any.h"

namespace antlr4 {
namespace tree {

  /// <summary>
  /// Represents a token that was consumed during resynchronization
  ///  rather than during a valid match operation. For example,
  ///  we will create this kind of a node during single token insertion
  ///  and deletion as well as during "consume until error recovery set"
  ///  upon no viable alternative exceptions.
  /// </summary>
  class ANTLR4CPP_PUBLIC ErrorNodeImpl : public ErrorNode {
  public:
    Token *symbol;

    explicit ErrorNodeImpl(Token *symbol) : ErrorNode(ParseTreeType::ERROR), symbol(symbol) {}

    virtual Token* getSymbol() const override;
    virtual void setParent(RuleContext *parent) override;
    virtual misc::Interval getSourceInterval() override;

    virtual std::any accept(ParseTreeVisitor *visitor) override;

    virtual std::string getText() override;
    virtual std::string toStringTree(Parser *parser, bool pretty = false) override;
    virtual std::string toString() override;
    virtual std::string toStringTree(bool pretty = false) override;
  };

} // namespace tree
} // namespace antlr4
