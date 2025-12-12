/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/SingletonPredictionContext.h"

#include "support/Casts.h"
#include "misc/MurmurHash.h"
#include "atn/HashUtils.h"

using namespace antlr4::atn;
using namespace antlrcpp;

SingletonPredictionContext::SingletonPredictionContext(Ref<const PredictionContext> parent, size_t returnState)
    : PredictionContext(PredictionContextType::SINGLETON), parent(std::move(parent)), returnState(returnState) {
  assert(returnState != ATNState::INVALID_STATE_NUMBER);
}

Ref<const SingletonPredictionContext> SingletonPredictionContext::create(Ref<const PredictionContext> parent, size_t returnState) {
  if (returnState == EMPTY_RETURN_STATE && parent == nullptr) {
    // someone can pass in the bits of an array ctx that mean $
    return std::dynamic_pointer_cast<const SingletonPredictionContext>(EMPTY);
  }
  return std::make_shared<SingletonPredictionContext>(std::move(parent), returnState);
}

bool SingletonPredictionContext::isEmpty() const {
  return parent == nullptr && returnState == EMPTY_RETURN_STATE;
}

size_t SingletonPredictionContext::size() const {
  return 1;
}

const Ref<const PredictionContext>& SingletonPredictionContext::getParent(size_t index) const {
  assert(index == 0);
  static_cast<void>(index);
  return parent;
}

size_t SingletonPredictionContext::getReturnState(size_t index) const {
  assert(index == 0);
  static_cast<void>(index);
  return returnState;
}

size_t SingletonPredictionContext::hashCodeImpl() const {
  size_t hash = misc::MurmurHash::initialize();
  hash = misc::MurmurHash::update(hash, static_cast<size_t>(getContextType()));
  hash = misc::MurmurHash::update(hash, parent);
  hash = misc::MurmurHash::update(hash, returnState);
  return misc::MurmurHash::finish(hash, 3);
}

bool SingletonPredictionContext::equals(const PredictionContext &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getContextType() != other.getContextType()) {
    return false;
  }
  const auto &singleton = downCast<const SingletonPredictionContext&>(other);
  return returnState == singleton.returnState &&
         cachedHashCodeEqual(cachedHashCode(), singleton.cachedHashCode()) &&
         (parent == singleton.parent || (parent != nullptr && singleton.parent != nullptr && *parent == *singleton.parent));
}

std::string SingletonPredictionContext::toString() const {
  //std::string up = !parent.expired() ? parent.lock()->toString() : "";
  std::string up = parent != nullptr ? parent->toString() : "";
  if (up.length() == 0) {
    if (returnState == EMPTY_RETURN_STATE) {
      return "$";
    }
    return std::to_string(returnState);
  }
  return std::to_string(returnState) + " " + up;
}
