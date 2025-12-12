/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/HashUtils.h"
#include "misc/MurmurHash.h"
#include "Lexer.h"
#include "support/CPPUtils.h"
#include "support/Casts.h"

#include "atn/LexerIndexedCustomAction.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

LexerIndexedCustomAction::LexerIndexedCustomAction(int offset, Ref<const LexerAction> action)
    : LexerAction(LexerActionType::INDEXED_CUSTOM, true), _action(std::move(action)), _offset(offset) {}

void LexerIndexedCustomAction::execute(Lexer *lexer) const {
  // assume the input stream position was properly set by the calling code
  getAction()->execute(lexer);
}

size_t LexerIndexedCustomAction::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getActionType()));
  hash = MurmurHash::update(hash, getOffset());
  hash = MurmurHash::update(hash, getAction());
  return MurmurHash::finish(hash, 3);
}

bool LexerIndexedCustomAction::equals(const LexerAction &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getActionType() != other.getActionType()) {
    return false;
  }
  const auto &lexerAction = downCast<const LexerIndexedCustomAction&>(other);
  return getOffset() == lexerAction.getOffset() &&
         cachedHashCodeEqual(cachedHashCode(), lexerAction.cachedHashCode()) &&
         *getAction() == *lexerAction.getAction();
}

std::string LexerIndexedCustomAction::toString() const {
  return "indexedCustom(" + std::to_string(getOffset()) + ", " + getAction()->toString() + ")";
}
