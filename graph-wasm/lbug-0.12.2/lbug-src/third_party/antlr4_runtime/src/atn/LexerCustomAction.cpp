/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "Lexer.h"
#include "support/Casts.h"

#include "atn/LexerCustomAction.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

LexerCustomAction::LexerCustomAction(size_t ruleIndex, size_t actionIndex)
    : LexerAction(LexerActionType::CUSTOM, true), _ruleIndex(ruleIndex), _actionIndex(actionIndex) {}

void LexerCustomAction::execute(Lexer *lexer) const {
  lexer->action(nullptr, getRuleIndex(), getActionIndex());
}

size_t LexerCustomAction::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getActionType()));
  hash = MurmurHash::update(hash, getRuleIndex());
  hash = MurmurHash::update(hash, getActionIndex());
  return MurmurHash::finish(hash, 3);
}

bool LexerCustomAction::equals(const LexerAction &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getActionType() != other.getActionType()) {
    return false;
  }
  const auto &lexerAction = downCast<const LexerCustomAction&>(other);
  return getRuleIndex() == lexerAction.getRuleIndex() && getActionIndex() == lexerAction.getActionIndex();
}

std::string LexerCustomAction::toString() const {
  return "custom(" + std::to_string(getRuleIndex()) + ", " + std::to_string(getActionIndex()) + ")";
}
