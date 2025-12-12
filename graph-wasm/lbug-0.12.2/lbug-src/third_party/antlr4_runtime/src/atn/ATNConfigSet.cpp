/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/PredictionContext.h"
#include "atn/ATNConfig.h"
#include "atn/ATNSimulator.h"
#include "Exceptions.h"
#include "atn/SemanticContext.h"
#include "support/Arrays.h"

#include "atn/ATNConfigSet.h"

using namespace antlr4::atn;
using namespace antlrcpp;

namespace {

}

ATNConfigSet::ATNConfigSet() : ATNConfigSet(true) {}

ATNConfigSet::ATNConfigSet(const ATNConfigSet &other)
    : fullCtx(other.fullCtx), _configLookup(other._configLookup.bucket_count(), ATNConfigHasher{this}, ATNConfigComparer{this}) {
  addAll(other);
  uniqueAlt = other.uniqueAlt;
  conflictingAlts = other.conflictingAlts;
  hasSemanticContext = other.hasSemanticContext;
  dipsIntoOuterContext = other.dipsIntoOuterContext;
}

ATNConfigSet::ATNConfigSet(bool fullCtx)
    : fullCtx(fullCtx), _configLookup(0, ATNConfigHasher{this}, ATNConfigComparer{this}) {}

bool ATNConfigSet::add(const Ref<ATNConfig> &config) {
  return add(config, nullptr);
}

bool ATNConfigSet::add(const Ref<ATNConfig> &config, PredictionContextMergeCache *mergeCache) {
  assert(config);

  if (_readonly) {
    throw IllegalStateException("This set is readonly");
  }
  if (config->semanticContext != SemanticContext::Empty::Instance) {
    hasSemanticContext = true;
  }
  if (config->getOuterContextDepth() > 0) {
    dipsIntoOuterContext = true;
  }

  auto existing = _configLookup.find(config.get());
  if (existing == _configLookup.end()) {
    _configLookup.insert(config.get());
    _cachedHashCode = 0;
    configs.push_back(config); // track order here

    return true;
  }

  // a previous (s,i,pi,_), merge with it and save result
  bool rootIsWildcard = !fullCtx;
  Ref<const PredictionContext> merged = PredictionContext::merge((*existing)->context, config->context, rootIsWildcard, mergeCache);
  // no need to check for existing.context, config.context in cache
  // since only way to create new graphs is "call rule" and here. We
  // cache at both places.
  (*existing)->reachesIntoOuterContext = std::max((*existing)->reachesIntoOuterContext, config->reachesIntoOuterContext);

  // make sure to preserve the precedence filter suppression during the merge
  if (config->isPrecedenceFilterSuppressed()) {
    (*existing)->setPrecedenceFilterSuppressed(true);
  }

  (*existing)->context = std::move(merged); // replace context; no need to alt mapping

  return true;
}

bool ATNConfigSet::addAll(const ATNConfigSet &other) {
  for (const auto &c : other.configs) {
    add(c);
  }
  return false;
}

std::vector<ATNState*> ATNConfigSet::getStates() const {
  std::vector<ATNState*> states;
  states.reserve(configs.size());
  for (const auto &c : configs) {
    states.push_back(c->state);
  }
  return states;
}

/**
 * Gets the complete set of represented alternatives for the configuration
 * set.
 *
 * @return the set of represented alternatives in this configuration set
 *
 * @since 4.3
 */

BitSet ATNConfigSet::getAlts() const {
  BitSet alts;
  for (const auto &config : configs) {
    alts.set(config->alt);
  }
  return alts;
}

std::vector<Ref<const SemanticContext>> ATNConfigSet::getPredicates() const {
  std::vector<Ref<const SemanticContext>> preds;
  preds.reserve(configs.size());
  for (const auto &c : configs) {
    if (c->semanticContext != SemanticContext::Empty::Instance) {
      preds.push_back(c->semanticContext);
    }
  }
  return preds;
}

const Ref<ATNConfig>& ATNConfigSet::get(size_t i) const {
  return configs[i];
}

void ATNConfigSet::optimizeConfigs(ATNSimulator *interpreter) {
  assert(interpreter);

  if (_readonly) {
    throw IllegalStateException("This set is readonly");
  }
  if (_configLookup.empty())
    return;

  for (const auto &config : configs) {
    config->context = interpreter->getCachedContext(config->context);
  }
}

bool ATNConfigSet::equals(const ATNConfigSet &other) const {
  if (&other == this) {
    return true;
  }

  if (configs.size() != other.configs.size())
    return false;

  if (fullCtx != other.fullCtx || uniqueAlt != other.uniqueAlt ||
      conflictingAlts != other.conflictingAlts || hasSemanticContext != other.hasSemanticContext ||
      dipsIntoOuterContext != other.dipsIntoOuterContext) // includes stack context
    return false;

  return Arrays::equals(configs, other.configs);
}

size_t ATNConfigSet::hashCode() const {
  size_t cachedHashCode = _cachedHashCode.load(std::memory_order_relaxed);
  if (!isReadonly() || cachedHashCode == 0) {
    cachedHashCode = 1;
    for (const auto &i : configs) {
      cachedHashCode = 31 * cachedHashCode + i->hashCode(); // Same as Java's list hashCode impl.
    }
    _cachedHashCode.store(cachedHashCode, std::memory_order_relaxed);
  }
  return cachedHashCode;
}

size_t ATNConfigSet::size() const {
  return configs.size();
}

bool ATNConfigSet::isEmpty() const {
  return configs.empty();
}

void ATNConfigSet::clear() {
  if (_readonly) {
    throw IllegalStateException("This set is readonly");
  }
  configs.clear();
  _cachedHashCode = 0;
  _configLookup.clear();
}

bool ATNConfigSet::isReadonly() const {
  return _readonly;
}

void ATNConfigSet::setReadonly(bool readonly) {
  _readonly = readonly;
  LookupContainer(0, ATNConfigHasher{this}, ATNConfigComparer{this}).swap(_configLookup);
}

std::string ATNConfigSet::toString() const {
  std::stringstream ss;
  ss << "[";
  for (size_t i = 0; i < configs.size(); i++) {
    if ( i>0 ) ss << ", ";
    ss << configs[i]->toString();
  }
  ss << "]";

  if (hasSemanticContext) {
    ss << ",hasSemanticContext=" << (hasSemanticContext?"true":"false");
  }
  if (uniqueAlt != ATN::INVALID_ALT_NUMBER) {
    ss << ",uniqueAlt=" << uniqueAlt;
  }

  if (conflictingAlts.count() > 0) {
    ss << ",conflictingAlts=";
    ss << conflictingAlts.toString();
  }

  if (dipsIntoOuterContext) {
    ss << ",dipsIntoOuterContext";
  }
  return ss.str();
}

size_t ATNConfigSet::hashCode(const ATNConfig &other) const {
  size_t hashCode = 7;
  hashCode = 31 * hashCode + other.state->stateNumber;
  hashCode = 31 * hashCode + other.alt;
  hashCode = 31 * hashCode + other.semanticContext->hashCode();
  return hashCode;
}

bool ATNConfigSet::equals(const ATNConfig &lhs, const ATNConfig &rhs) const {
  return lhs.state->stateNumber == rhs.state->stateNumber && lhs.alt == rhs.alt && *lhs.semanticContext == *rhs.semanticContext;
}
