/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ArrayPredictionContext.h"

#include <cstring>

#include "atn/SingletonPredictionContext.h"
#include "atn/HashUtils.h"
#include "misc/MurmurHash.h"
#include "support/Casts.h"

using namespace antlr4::atn;
using namespace antlr4::misc;
using namespace antlrcpp;

namespace {

  bool predictionContextEqual(const Ref<const PredictionContext> &lhs, const Ref<const PredictionContext> &rhs) {
    // parent PredictionContext pointers can be null during full context mode and
    // the ctxs are in an ArrayPredictionContext.  If both are null, return true
    // if just one is null, return false. If both are non-null, do comparison.
    if ( lhs == nullptr ) return rhs == nullptr;
    if ( rhs == nullptr ) return false; // lhs!=null and rhs==null
    return *lhs == *rhs;                // both nonnull
  }

}

ArrayPredictionContext::ArrayPredictionContext(const SingletonPredictionContext &predictionContext)
    : ArrayPredictionContext({ predictionContext.parent }, { predictionContext.returnState }) {}

ArrayPredictionContext::ArrayPredictionContext(std::vector<Ref<const PredictionContext>> parents,
                                               std::vector<size_t> returnStates)
    : PredictionContext(PredictionContextType::ARRAY), parents(std::move(parents)), returnStates(std::move(returnStates)) {
  assert(this->parents.size() > 0);
  assert(this->returnStates.size() > 0);
  assert(this->parents.size() == this->returnStates.size());
}

bool ArrayPredictionContext::isEmpty() const {
  // Since EMPTY_RETURN_STATE can only appear in the last position, we don't need to verify that size == 1.
  return returnStates[0] == EMPTY_RETURN_STATE;
}

size_t ArrayPredictionContext::size() const {
  return returnStates.size();
}

const Ref<const PredictionContext>& ArrayPredictionContext::getParent(size_t index) const {
  return parents[index];
}

size_t ArrayPredictionContext::getReturnState(size_t index) const {
  return returnStates[index];
}

size_t ArrayPredictionContext::hashCodeImpl() const {
  size_t hash = MurmurHash::initialize();
  hash = MurmurHash::update(hash, static_cast<size_t>(getContextType()));
  for (const auto &parent : parents) {
    hash = MurmurHash::update(hash, parent);
  }
  for (const auto &returnState : returnStates) {
    hash = MurmurHash::update(hash, returnState);
  }
  return MurmurHash::finish(hash, 1 + parents.size() + returnStates.size());
}

bool ArrayPredictionContext::equals(const PredictionContext &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  if (getContextType() != other.getContextType()) {
    return false;
  }
  const auto &array = downCast<const ArrayPredictionContext&>(other);
  const bool sameSize = returnStates.size() == array.returnStates.size() &&
                        parents.size() == array.parents.size();
  if ( !sameSize ) {
      return false;
  }

  const bool sameHash = cachedHashCodeEqual(cachedHashCode(), array.cachedHashCode());
  if ( !sameHash ) {
      return false;
  }

  const size_t stateSizeBytes = sizeof(decltype(returnStates)::value_type);
  const bool returnStateArraysEqual =
          std::memcmp(returnStates.data(), array.returnStates.data(),
                      returnStates.size() * stateSizeBytes) == 0;
  if ( !returnStateArraysEqual ) {
      return false;
  }

  // stack of contexts is the same
  const bool parentCtxEqual =
          std::equal(parents.begin(), parents.end(), array.parents.begin(), predictionContextEqual);
  return parentCtxEqual;
}

std::string ArrayPredictionContext::toString() const {
  if (isEmpty()) {
    return "[]";
  }

  std::stringstream ss;
  ss << "[";
  for (size_t i = 0; i < returnStates.size(); i++) {
    if (i > 0) {
      ss << ", ";
    }
    if (returnStates[i] == EMPTY_RETURN_STATE) {
      ss << "$";
      continue;
    }
    ss << returnStates[i];
    if (parents[i] != nullptr) {
      ss << " " << parents[i]->toString();
    } else {
      ss << "nul";
    }
  }
  ss << "]";
  return ss.str();
}
