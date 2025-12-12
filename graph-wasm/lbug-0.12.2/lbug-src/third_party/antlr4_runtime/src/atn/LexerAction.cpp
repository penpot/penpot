#include "LexerAction.h"

using namespace antlr4::atn;

size_t LexerAction::hashCode() const {
  auto hash = cachedHashCode();
  if (hash == 0) {
    hash = hashCodeImpl();
    if (hash == 0) {
      hash = std::numeric_limits<size_t>::max();
    }
    _hashCode.store(hash, std::memory_order_relaxed);
  }
  return hash;
}
