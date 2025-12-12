/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "atn/LexerIndexedCustomAction.h"
#include "atn/HashUtils.h"
#include "support/CPPUtils.h"
#include "support/Arrays.h"
#include "support/Casts.h"

#include "atn/LexerActionExecutor.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

namespace {

  bool lexerActionEqual(const Ref<const LexerAction> &lhs, const Ref<const LexerAction> &rhs) {
    return *lhs == *rhs;
  }

}

LexerActionExecutor::LexerActionExecutor(std::vector<Ref<const LexerAction>> lexerActions)
    : _lexerActions(std::move(lexerActions)), _hashCode(0) {}

Ref<const LexerActionExecutor> LexerActionExecutor::append(const Ref<const LexerActionExecutor> &lexerActionExecutor,
                                                           Ref<const LexerAction> lexerAction) {
  if (lexerActionExecutor == nullptr) {
    return std::make_shared<LexerActionExecutor>(std::vector<Ref<const LexerAction>>{ std::move(lexerAction) });
  }
  std::vector<Ref<const LexerAction>> lexerActions;
  lexerActions.reserve(lexerActionExecutor->_lexerActions.size() + 1);
  lexerActions.insert(lexerActions.begin(), lexerActionExecutor->_lexerActions.begin(), lexerActionExecutor->_lexerActions.end());
  lexerActions.push_back(std::move(lexerAction));
  return std::make_shared<LexerActionExecutor>(std::move(lexerActions));
}

Ref<const LexerActionExecutor> LexerActionExecutor::fixOffsetBeforeMatch(int offset) const {
  std::vector<Ref<const LexerAction>> updatedLexerActions;
  for (size_t i = 0; i < _lexerActions.size(); i++) {
    if (_lexerActions[i]->isPositionDependent() && !LexerIndexedCustomAction::is(*_lexerActions[i])) {
      if (updatedLexerActions.empty()) {
        updatedLexerActions = _lexerActions; // Make a copy.
      }
      updatedLexerActions[i] = std::make_shared<LexerIndexedCustomAction>(offset, _lexerActions[i]);
    }
  }
  if (updatedLexerActions.empty()) {
    return shared_from_this();
  }
  return std::make_shared<LexerActionExecutor>(std::move(updatedLexerActions));
}

const std::vector<Ref<const LexerAction>>& LexerActionExecutor::getLexerActions() const {
  return _lexerActions;
}

void LexerActionExecutor::execute(Lexer *lexer, CharStream *input, size_t startIndex) const {
  bool requiresSeek = false;
  size_t stopIndex = input->index();

  auto onExit = finally([requiresSeek, input, stopIndex]() {
    if (requiresSeek) {
      input->seek(stopIndex);
    }
  });
  for (const auto &lexerAction : _lexerActions) {
    if (LexerIndexedCustomAction::is(*lexerAction)) {
      int offset = downCast<const LexerIndexedCustomAction&>(*lexerAction).getOffset();
      input->seek(startIndex + offset);
      requiresSeek = (startIndex + offset) != stopIndex;
    } else if (lexerAction->isPositionDependent()) {
      input->seek(stopIndex);
      requiresSeek = false;
    }
    lexerAction->execute(lexer);
  }
}

size_t LexerActionExecutor::hashCode() const {
  auto hash = _hashCode.load(std::memory_order_relaxed);
  if (hash == 0) {
    hash = MurmurHash::initialize();
    for (const auto &lexerAction : _lexerActions) {
      hash = MurmurHash::update(hash, lexerAction);
    }
    hash = MurmurHash::finish(hash, _lexerActions.size());
    if (hash == 0) {
      hash = std::numeric_limits<size_t>::max();
    }
    _hashCode.store(hash, std::memory_order_relaxed);
  }
  return hash;
}

bool LexerActionExecutor::equals(const LexerActionExecutor &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  return cachedHashCodeEqual(_hashCode.load(std::memory_order_relaxed), other._hashCode.load(std::memory_order_relaxed)) &&
         _lexerActions.size() == other._lexerActions.size() &&
         std::equal(_lexerActions.begin(), _lexerActions.end(), other._lexerActions.begin(), lexerActionEqual);
}
