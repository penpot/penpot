/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "Lexer.h"
#include "support/Casts.h"

#include "atn/LexerModeAction.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

LexerModeAction::LexerModeAction(int mode) : LexerAction(LexerActionType::MODE, false), _mode(mode) {}

void LexerModeAction::execute(Lexer *lexer) const {
  lexer->setMode(getMode());
}

size_t LexerModeAction::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getActionType()));
  hash = MurmurHash::update(hash, getMode());
  return MurmurHash::finish(hash, 2);
}

bool LexerModeAction::equals(const LexerAction &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getActionType() != other.getActionType()) {
    return false;
  }
  const auto &lexerAction = downCast<const LexerModeAction&>(other);
  return getMode() == lexerAction.getMode();
}

std::string LexerModeAction::toString() const {
  return "mode(" + std::to_string(getMode()) + ")";
}
