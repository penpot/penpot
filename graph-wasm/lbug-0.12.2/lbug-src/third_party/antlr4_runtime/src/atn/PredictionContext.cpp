/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/SingletonPredictionContext.h"
#include "misc/MurmurHash.h"
#include "atn/ArrayPredictionContext.h"
#include "atn/PredictionContextCache.h"
#include "atn/PredictionContextMergeCache.h"
#include "RuleContext.h"
#include "ParserRuleContext.h"
#include "atn/RuleTransition.h"
#include "support/Arrays.h"
#include "support/CPPUtils.h"
#include "support/Casts.h"

#include "atn/PredictionContext.h"

using namespace antlr4;
using namespace antlr4::misc;
using namespace antlr4::atn;
using namespace antlrcpp;

namespace {

  void combineCommonParents(std::vector<Ref<const PredictionContext>> &parents) {
    std::unordered_set<Ref<const PredictionContext>> uniqueParents;
    uniqueParents.reserve(parents.size());
    for (const auto &parent : parents) {
      uniqueParents.insert(parent);
    }
    for (auto &parent : parents) {
      parent = *uniqueParents.find(parent);
    }
  }

  Ref<const PredictionContext> getCachedContextImpl(const Ref<const PredictionContext> &context,
                                                    PredictionContextCache &contextCache,
                                                    std::unordered_map<Ref<const PredictionContext>,
                                                    Ref<const PredictionContext>> &visited) {
    if (context->isEmpty()) {
      return context;
    }

    {
      auto iterator = visited.find(context);
      if (iterator != visited.end()) {
        return iterator->second; // Not necessarly the same as context.
      }
    }

    auto cached = contextCache.get(context);
    if (cached) {
      visited[context] = cached;
      return cached;
    }

    bool changed = false;

    std::vector<Ref<const PredictionContext>> parents(context->size());
    for (size_t i = 0; i < parents.size(); i++) {
      auto parent = getCachedContextImpl(context->getParent(i), contextCache, visited);
      if (changed || parent != context->getParent(i)) {
        if (!changed) {
          parents.clear();
          for (size_t j = 0; j < context->size(); j++) {
            parents.push_back(context->getParent(j));
          }

          changed = true;
        }

        parents[i] = std::move(parent);
      }
    }

    if (!changed) {
      visited[context] = context;
      contextCache.put(context);
      return context;
    }

    Ref<const PredictionContext> updated;
    if (parents.empty()) {
      updated = PredictionContext::EMPTY;
    } else if (parents.size() == 1) {
      updated = SingletonPredictionContext::create(std::move(parents[0]), context->getReturnState(0));
      contextCache.put(updated);
    } else {
      updated = std::make_shared<ArrayPredictionContext>(std::move(parents), downCast<const ArrayPredictionContext*>(context.get())->returnStates);
      contextCache.put(updated);
    }

    visited[updated] = updated;
    visited[context] = updated;

    return updated;
  }

  void getAllContextNodesImpl(const Ref<const PredictionContext> &context,
                              std::vector<Ref<const PredictionContext>> &nodes,
                              std::unordered_set<const PredictionContext*> &visited) {

    if (visited.find(context.get()) != visited.end()) {
      return; // Already done.
    }

    visited.insert(context.get());
    nodes.push_back(context);

    for (size_t i = 0; i < context->size(); i++) {
      getAllContextNodesImpl(context->getParent(i), nodes, visited);
    }
  }

  size_t insertOrAssignNodeId(std::unordered_map<const PredictionContext*, size_t> &nodeIds, size_t &nodeId, const PredictionContext *node) {
    auto existing = nodeIds.find(node);
    if (existing != nodeIds.end()) {
      return existing->second;
    }
    return nodeIds.insert({node, nodeId++}).first->second;
  }

}

const Ref<const PredictionContext> PredictionContext::EMPTY = std::make_shared<SingletonPredictionContext>(nullptr, PredictionContext::EMPTY_RETURN_STATE);

//----------------- PredictionContext ----------------------------------------------------------------------------------

PredictionContext::PredictionContext(PredictionContextType contextType) : _contextType(contextType), _hashCode(0) {}

PredictionContext::PredictionContext(PredictionContext&& other) : _contextType(other._contextType), _hashCode(other._hashCode.exchange(0, std::memory_order_relaxed)) {}

Ref<const PredictionContext> PredictionContext::fromRuleContext(const ATN &atn, RuleContext *outerContext) {
  if (outerContext == nullptr) {
    return PredictionContext::EMPTY;
  }

  // if we are in RuleContext of start rule, s, then PredictionContext
  // is EMPTY. Nobody called us. (if we are empty, return empty)
  if (outerContext->parent == nullptr || outerContext == &ParserRuleContext::EMPTY) {
    return PredictionContext::EMPTY;
  }

  // If we have a parent, convert it to a PredictionContext graph
  auto parent = PredictionContext::fromRuleContext(atn, RuleContext::is(outerContext->parent) ? downCast<RuleContext*>(outerContext->parent) : nullptr);
  const auto *transition = downCast<const RuleTransition*>(atn.states[outerContext->invokingState]->transitions[0].get());
  return SingletonPredictionContext::create(std::move(parent), transition->followState->stateNumber);
}

bool PredictionContext::hasEmptyPath() const {
  // since EMPTY_RETURN_STATE can only appear in the last position, we check last one
  return getReturnState(size() - 1) == EMPTY_RETURN_STATE;
}

size_t PredictionContext::hashCode() const {
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

Ref<const PredictionContext> PredictionContext::merge(Ref<const PredictionContext> a, Ref<const PredictionContext> b,
                                                      bool rootIsWildcard, PredictionContextMergeCache *mergeCache) {
  assert(a && b);

  // share same graph if both same
  if (a == b || *a == *b) {
    return a;
  }

  const auto aType = a->getContextType();
  const auto bType = b->getContextType();

  if (aType == PredictionContextType::SINGLETON && bType == PredictionContextType::SINGLETON) {
    return mergeSingletons(std::static_pointer_cast<const SingletonPredictionContext>(std::move(a)),
                           std::static_pointer_cast<const SingletonPredictionContext>(std::move(b)), rootIsWildcard, mergeCache);
  }

  // At least one of a or b is array.
  // If one is $ and rootIsWildcard, return $ as * wildcard.
  if (rootIsWildcard) {
    if (a == PredictionContext::EMPTY) {
      return a;
    }
    if (b == PredictionContext::EMPTY) {
      return b;
    }
  }

  // convert singleton so both are arrays to normalize
  Ref<const ArrayPredictionContext> left;
  if (aType == PredictionContextType::SINGLETON) {
    left = std::make_shared<ArrayPredictionContext>(downCast<const SingletonPredictionContext&>(*a));
  } else {
    left = std::static_pointer_cast<const ArrayPredictionContext>(std::move(a));
  }
  Ref<const ArrayPredictionContext> right;
  if (bType == PredictionContextType::SINGLETON) {
    right = std::make_shared<ArrayPredictionContext>(downCast<const SingletonPredictionContext&>(*b));
  } else {
    right = std::static_pointer_cast<const ArrayPredictionContext>(std::move(b));
  }
  return mergeArrays(std::move(left), std::move(right), rootIsWildcard, mergeCache);
}

Ref<const PredictionContext> PredictionContext::mergeSingletons(Ref<const SingletonPredictionContext> a, Ref<const SingletonPredictionContext> b,
                                                                bool rootIsWildcard, PredictionContextMergeCache *mergeCache) {

  if (mergeCache) {
    auto existing = mergeCache->get(a, b);
    if (existing) {
      return existing;
    }
    existing = mergeCache->get(b, a);
    if (existing) {
      return existing;
    }
  }

  auto rootMerge = mergeRoot(a, b, rootIsWildcard);
  if (rootMerge) {
    if (mergeCache) {
      return mergeCache->put(a, b, std::move(rootMerge));
    }
    return rootMerge;
  }

  const auto& parentA = a->parent;
  const auto& parentB = b->parent;
  if (a->returnState == b->returnState) { // a == b
    auto parent = merge(parentA, parentB, rootIsWildcard, mergeCache);

    // If parent is same as existing a or b parent or reduced to a parent, return it.
    if (parent == parentA) { // ax + bx = ax, if a=b
      return a;
    }
    if (parent == parentB) { // ax + bx = bx, if a=b
      return b;
    }

    // else: ax + ay = a'[x,y]
    // merge parents x and y, giving array node with x,y then remainders
    // of those graphs.  dup a, a' points at merged array
    // new joined parent so create new singleton pointing to it, a'
    auto c = SingletonPredictionContext::create(std::move(parent), a->returnState);
    if (mergeCache) {
      return mergeCache->put(a, b, std::move(c));
    }
    return c;
  }
  // a != b payloads differ
  // see if we can collapse parents due to $+x parents if local ctx
  Ref<const PredictionContext> singleParent;
  if (a == b || (*parentA == *parentB)) { // ax + bx = [a,b]x
    singleParent = parentA;
  }
  if (singleParent) { // parents are same, sort payloads and use same parent
    std::vector<size_t> payloads = { a->returnState, b->returnState };
    if (a->returnState > b->returnState) {
      payloads[0] = b->returnState;
      payloads[1] = a->returnState;
    }
    std::vector<Ref<const PredictionContext>> parents = { singleParent, singleParent };
    auto c = std::make_shared<ArrayPredictionContext>(std::move(parents), std::move(payloads));
    if (mergeCache) {
      return mergeCache->put(a, b, std::move(c));
    }
    return c;
  }

  // parents differ and can't merge them. Just pack together
  // into array; can't merge.
  // ax + by = [ax,by]
  if (a->returnState > b->returnState) { // sort by payload
    std::vector<size_t> payloads = { b->returnState, a->returnState };
    std::vector<Ref<const PredictionContext>> parents = { b->parent, a->parent };
    auto c = std::make_shared<ArrayPredictionContext>(std::move(parents), std::move(payloads));
    if (mergeCache) {
      return mergeCache->put(a, b, std::move(c));
    }
    return c;
  }
  std::vector<size_t> payloads = {a->returnState, b->returnState};
  std::vector<Ref<const PredictionContext>> parents = { a->parent, b->parent };
  auto c = std::make_shared<ArrayPredictionContext>(std::move(parents), std::move(payloads));
  if (mergeCache) {
    return mergeCache->put(a, b, std::move(c));
  }
  return c;
}

Ref<const PredictionContext> PredictionContext::mergeRoot(Ref<const SingletonPredictionContext> a, Ref<const SingletonPredictionContext> b,
                                                          bool rootIsWildcard) {
  if (rootIsWildcard) {
    if (a == EMPTY) { // * + b = *
      return EMPTY;
    }
    if (b == EMPTY) { // a + * = *
      return EMPTY;
    }
  } else {
    if (a == EMPTY && b == EMPTY) { // $ + $ = $
      return EMPTY;
    }
    if (a == EMPTY) { // $ + x = [$,x]
      std::vector<size_t> payloads = { b->returnState, EMPTY_RETURN_STATE };
      std::vector<Ref<const PredictionContext>> parents = { b->parent, nullptr };
      return std::make_shared<ArrayPredictionContext>(std::move(parents), std::move(payloads));
    }
    if (b == EMPTY) { // x + $ = [$,x] ($ is always first if present)
      std::vector<size_t> payloads = { a->returnState, EMPTY_RETURN_STATE };
      std::vector<Ref<const PredictionContext>> parents = { a->parent, nullptr };
      return std::make_shared<ArrayPredictionContext>(std::move(parents), std::move(payloads));
    }
  }
  return nullptr;
}

Ref<const PredictionContext> PredictionContext::mergeArrays(Ref<const ArrayPredictionContext> a, Ref<const ArrayPredictionContext> b,
                                                            bool rootIsWildcard, PredictionContextMergeCache *mergeCache) {
  if (mergeCache) {
    auto existing = mergeCache->get(a, b);
    if (existing) {
#if TRACE_ATN_SIM == 1
      std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> previous" << std::endl;
#endif
      return existing;
    }
    existing = mergeCache->get(b, a);
    if (existing) {
#if TRACE_ATN_SIM == 1
        std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> previous" << std::endl;
#endif
      return existing;
    }
  }

  // merge sorted payloads a + b => M
  size_t i = 0; // walks a
  size_t j = 0; // walks b
  size_t k = 0; // walks target M array

  std::vector<size_t> mergedReturnStates(a->returnStates.size() + b->returnStates.size());
  std::vector<Ref<const PredictionContext>> mergedParents(a->returnStates.size() + b->returnStates.size());

  // walk and merge to yield mergedParents, mergedReturnStates
  while (i < a->returnStates.size() && j < b->returnStates.size()) {
    const auto& parentA = a->parents[i];
    const auto& parentB = b->parents[j];
    if (a->returnStates[i] == b->returnStates[j]) {
      // same payload (stack tops are equal), must yield merged singleton
      size_t payload = a->returnStates[i];
      // $+$ = $
      bool both$ = payload == EMPTY_RETURN_STATE && !parentA && !parentB;
      bool ax_ax = (parentA && parentB) && *parentA == *parentB; // ax+ax -> ax
      if (both$ || ax_ax) {
        mergedParents[k] = parentA; // choose left
        mergedReturnStates[k] = payload;
      } else { // ax+ay -> a'[x,y]
        mergedParents[k] = merge(parentA, parentB, rootIsWildcard, mergeCache);
        mergedReturnStates[k] = payload;
      }
      i++; // hop over left one as usual
      j++; // but also skip one in right side since we merge
    } else if (a->returnStates[i] < b->returnStates[j]) { // copy a[i] to M
      mergedParents[k] = parentA;
      mergedReturnStates[k] = a->returnStates[i];
      i++;
    } else { // b > a, copy b[j] to M
      mergedParents[k] = parentB;
      mergedReturnStates[k] = b->returnStates[j];
      j++;
    }
    k++;
  }

  // copy over any payloads remaining in either array
  if (i < a->returnStates.size()) {
    for (auto p = i; p < a->returnStates.size(); p++) {
      mergedParents[k] = a->parents[p];
      mergedReturnStates[k] = a->returnStates[p];
      k++;
    }
  } else {
    for (auto p = j; p < b->returnStates.size(); p++) {
      mergedParents[k] = b->parents[p];
      mergedReturnStates[k] = b->returnStates[p];
      k++;
    }
  }

  // trim merged if we combined a few that had same stack tops
  if (k < mergedParents.size()) { // write index < last position; trim
    if (k == 1) { // for just one merged element, return singleton top
      auto c = SingletonPredictionContext::create(std::move(mergedParents[0]), mergedReturnStates[0]);
      if (mergeCache) {
        return mergeCache->put(a, b, std::move(c));
      }
      return c;
    }
    mergedParents.resize(k);
    mergedReturnStates.resize(k);
  }

  ArrayPredictionContext m(std::move(mergedParents), std::move(mergedReturnStates));

  // if we created same array as a or b, return that instead
  // TODO: track whether this is possible above during merge sort for speed
  if (m == *a) {
    if (mergeCache) {
#if TRACE_ATN_SIM == 1
      std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> a" << std::endl;
#endif
      return mergeCache->put(a, b, a);
    }
#if TRACE_ATN_SIM == 1
    std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> a" << std::endl;
#endif
    return a;
  }
  if (m == *b) {
    if (mergeCache) {
#if TRACE_ATN_SIM == 1
        std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> b" << std::endl;
#endif
      return mergeCache->put(a, b, b);
    }
#if TRACE_ATN_SIM == 1
      std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> b" << std::endl;
#endif
    return b;
  }

  combineCommonParents(m.parents);
  auto c = std::make_shared<ArrayPredictionContext>(std::move(m));

#if TRACE_ATN_SIM == 1
    std::cout << "mergeArrays a=" << a->toString() << ",b=" << b->toString() << " -> " << c->toString() << std::endl;
#endif

  if (mergeCache) {
    return mergeCache->put(a, b, std::move(c));
  }
  return c;
}

std::string PredictionContext::toDOTString(const Ref<const PredictionContext> &context) {
  if (context == nullptr) {
    return "";
  }

  std::stringstream ss;
  ss << "digraph G {\n" << "rankdir=LR;\n";

  std::vector<Ref<const PredictionContext>> nodes = getAllContextNodes(context);
  std::unordered_map<const PredictionContext*, size_t> nodeIds;
  size_t nodeId = 0;

  for (const auto &current : nodes) {
    if (current->getContextType() == PredictionContextType::SINGLETON) {
      std::string s = std::to_string(insertOrAssignNodeId(nodeIds, nodeId, current.get()));
      ss << "  s" << s;
      std::string returnState = std::to_string(current->getReturnState(0));
      if (current == PredictionContext::EMPTY) {
        returnState = "$";
      }
      ss << " [label=\"" << returnState << "\"];\n";
      continue;
    }
    Ref<const ArrayPredictionContext> arr = std::static_pointer_cast<const ArrayPredictionContext>(current);
    ss << "  s" << insertOrAssignNodeId(nodeIds, nodeId, arr.get()) << " [shape=box, label=\"" << "[";
    bool first = true;
    for (auto inv : arr->returnStates) {
      if (!first) {
       ss << ", ";
      }
      if (inv == EMPTY_RETURN_STATE) {
        ss << "$";
      } else {
        ss << inv;
      }
      first = false;
    }
    ss << "]";
    ss << "\"];\n";
  }

  for (const auto &current : nodes) {
    if (current == EMPTY) {
      continue;
    }
    for (size_t i = 0; i < current->size(); i++) {
      if (!current->getParent(i)) {
        continue;
      }
      ss << "  s" << insertOrAssignNodeId(nodeIds, nodeId, current.get()) << "->" << "s" << insertOrAssignNodeId(nodeIds, nodeId, current->getParent(i).get());
      if (current->size() > 1) {
        ss << " [label=\"parent[" << i << "]\"];\n";
      } else {
        ss << ";\n";
      }
    }
  }

  ss << "}\n";
  return ss.str();
}

// The "visited" map is just a temporary structure to control the retrieval process (which is recursive).
Ref<const PredictionContext> PredictionContext::getCachedContext(const Ref<const PredictionContext> &context,
                                                                 PredictionContextCache &contextCache) {
  std::unordered_map<Ref<const PredictionContext>, Ref<const PredictionContext>> visited;
  return getCachedContextImpl(context, contextCache, visited);
}

std::vector<Ref<const PredictionContext>> PredictionContext::getAllContextNodes(const Ref<const PredictionContext> &context) {
  std::vector<Ref<const PredictionContext>> nodes;
  std::unordered_set<const PredictionContext*> visited;
  getAllContextNodesImpl(context, nodes, visited);
  return nodes;
}

std::vector<std::string> PredictionContext::toStrings(Recognizer *recognizer, int currentState) const {
  return toStrings(recognizer, EMPTY, currentState);
}

std::vector<std::string> PredictionContext::toStrings(Recognizer *recognizer, const Ref<const PredictionContext> &stop, int currentState) const {

  std::vector<std::string> result;

  for (size_t perm = 0; ; perm++) {
    size_t offset = 0;
    bool last = true;
    const PredictionContext *p = this;
    size_t stateNumber = currentState;

    std::stringstream ss;
    ss << "[";
    bool outerContinue = false;
    while (!p->isEmpty() && p != stop.get()) {
      size_t index = 0;
      if (p->size() > 0) {
        size_t bits = 1;
        while ((1ULL << bits) < p->size()) {
          bits++;
        }

        size_t mask = (1 << bits) - 1;
        index = (perm >> offset) & mask;
        last &= index >= p->size() - 1;
        if (index >= p->size()) {
          outerContinue = true;
          break;
        }
        offset += bits;
      }

      if (recognizer != nullptr) {
        if (ss.tellp() > 1) {
          // first char is '[', if more than that this isn't the first rule
          ss << ' ';
        }

        const ATN &atn = recognizer->getATN();
        ATNState *s = atn.states[stateNumber];
        std::string ruleName = recognizer->getRuleNames()[s->ruleIndex];
        ss << ruleName;
      } else if (p->getReturnState(index) != EMPTY_RETURN_STATE) {
        if (!p->isEmpty()) {
          if (ss.tellp() > 1) {
            // first char is '[', if more than that this isn't the first rule
            ss << ' ';
          }

          ss << p->getReturnState(index);
        }
      }
      stateNumber = p->getReturnState(index);
      p = p->getParent(index).get();
    }

    if (outerContinue)
      continue;

    ss << "]";
    result.push_back(ss.str());

    if (last) {
      break;
    }
  }

  return result;
}
