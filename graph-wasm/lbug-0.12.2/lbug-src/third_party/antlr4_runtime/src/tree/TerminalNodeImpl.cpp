/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/Interval.h"
#include "Token.h"
#include "RuleContext.h"
#include "tree/ParseTreeVisitor.h"

#include "tree/TerminalNodeImpl.h"

using namespace antlr4;
using namespace antlr4::tree;

Token* TerminalNodeImpl::getSymbol() const {
  return symbol;
}

void TerminalNodeImpl::setParent(RuleContext *parent_) {
  this->parent = parent_;
}

misc::Interval TerminalNodeImpl::getSourceInterval() {
  if (symbol == nullptr) {
    return misc::Interval::INVALID;
  }

  size_t tokenIndex = symbol->getTokenIndex();
  return misc::Interval(tokenIndex, tokenIndex);
}

std::any TerminalNodeImpl::accept(ParseTreeVisitor *visitor) {
  return visitor->visitTerminal(this);
}

std::string TerminalNodeImpl::getText() {
  return symbol->getText();
}

std::string TerminalNodeImpl::toStringTree(Parser * /*parser*/, bool /*pretty*/) {
  return toString();
}

std::string TerminalNodeImpl::toString() {
  if (symbol->getType() == Token::EOF) {
    return "<EOF>";
  }
  return symbol->getText();
}

std::string TerminalNodeImpl::toStringTree(bool /*pretty*/) {
  return toString();
}
