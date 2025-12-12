/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "Lexer.h"

#include "atn/LexerPopModeAction.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;

const Ref<const LexerPopModeAction>& LexerPopModeAction::getInstance() {
  static const Ref<const LexerPopModeAction> instance(new LexerPopModeAction());
  return instance;
}

void LexerPopModeAction::execute(Lexer *lexer) const {
  lexer->popMode();
}

size_t LexerPopModeAction::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getActionType()));
  return MurmurHash::finish(hash, 1);
}

bool LexerPopModeAction::equals(const LexerAction &other) const {
  return this == std::addressof(other);
}

std::string LexerPopModeAction::toString() const {
  return "popMode";
}
