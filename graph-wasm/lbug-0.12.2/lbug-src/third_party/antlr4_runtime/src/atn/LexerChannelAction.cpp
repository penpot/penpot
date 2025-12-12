/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "Lexer.h"
#include "support/Casts.h"

#include "atn/LexerChannelAction.h"

using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

LexerChannelAction::LexerChannelAction(int channel)
    : LexerAction(LexerActionType::CHANNEL, false), _channel(channel) {}

void LexerChannelAction::execute(Lexer *lexer) const {
  lexer->setChannel(getChannel());
}

size_t LexerChannelAction::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getActionType()));
  hash = MurmurHash::update(hash, getChannel());
  return MurmurHash::finish(hash, 2);
}

bool LexerChannelAction::equals(const LexerAction &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getActionType() != other.getActionType()) {
    return false;
  }
  const auto &lexerAction = downCast<const LexerChannelAction&>(other);
  return getChannel() == lexerAction.getChannel();
}

std::string LexerChannelAction::toString() const {
  return "channel(" + std::to_string(getChannel()) + ")";
}
