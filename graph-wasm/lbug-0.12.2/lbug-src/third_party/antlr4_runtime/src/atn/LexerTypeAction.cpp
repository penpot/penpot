/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "Lexer.h"
#include "support/Casts.h"

#include "atn/LexerTypeAction.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

LexerTypeAction::LexerTypeAction(int type) : LexerAction(LexerActionType::TYPE, false), _type(type) {}

void LexerTypeAction::execute(Lexer *lexer) const {
  lexer->setType(getType());
}

size_t LexerTypeAction::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getActionType()));
  hash = MurmurHash::update(hash, getType());
  return MurmurHash::finish(hash, 2);
}

bool LexerTypeAction::equals(const LexerAction &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getActionType() != other.getActionType()) {
    return false;
  }
  const auto &lexerAction = downCast<const LexerTypeAction&>(other);
  return getType() == lexerAction.getType();
}

std::string LexerTypeAction::toString() const {
  return "type(" + std::to_string(getType()) + ")";
}
