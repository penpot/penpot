/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/ATNConfigSet.h"
#include "atn/SemanticContext.h"
#include "atn/ATNConfig.h"
#include "misc/MurmurHash.h"

#include "dfa/DFAState.h"

using namespace antlr4::dfa;
using namespace antlr4::atn;

std::string DFAState::PredPrediction::toString() const {
  return std::string("(") + pred->toString() + ", " + std::to_string(alt) + ")";
}

std::set<size_t> DFAState::getAltSet() const {
  std::set<size_t> alts;
  if (configs != nullptr) {
    for (size_t i = 0; i < configs->size(); i++) {
      alts.insert(configs->get(i)->alt);
    }
  }
  return alts;
}

size_t DFAState::hashCode() const {
  return configs != nullptr ? configs->hashCode() : 0;
}

bool DFAState::equals(const DFAState &other) const {
  if (this == std::addressof(other)) {
    return true;
  }
  return configs == other.configs ||
         (configs != nullptr && other.configs != nullptr && *configs == *other.configs);
}

std::string DFAState::toString() const {
  std::stringstream ss;
  ss << stateNumber;
  if (configs) {
    ss << ":" << configs->toString();
  }
  if (isAcceptState) {
    ss << "=>";
    if (!predicates.empty()) {
      for (size_t i = 0; i < predicates.size(); i++) {
        ss << predicates[i].toString();
      }
    } else {
      ss << prediction;
    }
  }
  return ss.str();
}
