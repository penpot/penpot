/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "misc/MurmurHash.h"
#include "atn/PredictionContext.h"
#include "SemanticContext.h"

#include "atn/ATNConfig.h"

using namespace antlr4::atn;

namespace {

/**
 * This field stores the bit mask for implementing the
 * {@link #isPrecedenceFilterSuppressed} property as a bit within the
 * existing {@link #reachesIntoOuterContext} field.
 */
inline constexpr size_t SUPPRESS_PRECEDENCE_FILTER = 0x40000000;

}

ATNConfig::ATNConfig(ATNState *state, size_t alt, Ref<const PredictionContext> context)
    : ATNConfig(state, alt, std::move(context), 0, SemanticContext::Empty::Instance) {}

ATNConfig::ATNConfig(ATNState *state, size_t alt, Ref<const PredictionContext> context, Ref<const SemanticContext> semanticContext)
    : ATNConfig(state, alt, std::move(context), 0, std::move(semanticContext)) {}

ATNConfig::ATNConfig(ATNConfig const& other, Ref<const SemanticContext> semanticContext)
    : ATNConfig(other.state, other.alt, other.context, other.reachesIntoOuterContext, std::move(semanticContext)) {}

ATNConfig::ATNConfig(ATNConfig const& other, ATNState *state)
    : ATNConfig(state, other.alt, other.context, other.reachesIntoOuterContext, other.semanticContext) {}

ATNConfig::ATNConfig(ATNConfig const& other, ATNState *state, Ref<const SemanticContext> semanticContext)
    : ATNConfig(state, other.alt, other.context, other.reachesIntoOuterContext, std::move(semanticContext)) {}

ATNConfig::ATNConfig(ATNConfig const& other, ATNState *state, Ref<const PredictionContext> context)
    : ATNConfig(state, other.alt, std::move(context), other.reachesIntoOuterContext, other.semanticContext) {}

ATNConfig::ATNConfig(ATNConfig const& other, ATNState *state, Ref<const PredictionContext> context, Ref<const SemanticContext> semanticContext)
    : ATNConfig(state, other.alt, std::move(context), other.reachesIntoOuterContext, std::move(semanticContext)) {}

ATNConfig::ATNConfig(ATNState *state, size_t alt, Ref<const PredictionContext> context, size_t reachesIntoOuterContext, Ref<const SemanticContext> semanticContext)
    : state(state), alt(alt), context(std::move(context)), reachesIntoOuterContext(reachesIntoOuterContext), semanticContext(std::move(semanticContext)) {}

size_t ATNConfig::hashCode() const {
  size_t hashCode = misc::MurmurHash::initialize(7);
  hashCode = misc::MurmurHash::update(hashCode, state->stateNumber);
  hashCode = misc::MurmurHash::update(hashCode, alt);
  hashCode = misc::MurmurHash::update(hashCode, context);
  hashCode = misc::MurmurHash::update(hashCode, semanticContext);
  hashCode = misc::MurmurHash::finish(hashCode, 4);
  return hashCode;
}

size_t ATNConfig::getOuterContextDepth() const {
  return reachesIntoOuterContext & ~SUPPRESS_PRECEDENCE_FILTER;
}

bool ATNConfig::isPrecedenceFilterSuppressed() const {
  return (reachesIntoOuterContext & SUPPRESS_PRECEDENCE_FILTER) != 0;
}

void ATNConfig::setPrecedenceFilterSuppressed(bool value) {
  if (value) {
    reachesIntoOuterContext |= SUPPRESS_PRECEDENCE_FILTER;
  } else {
    reachesIntoOuterContext &= ~SUPPRESS_PRECEDENCE_FILTER;
  }
}

bool ATNConfig::operator==(const ATNConfig &other) const {
  return state->stateNumber == other.state->stateNumber && alt == other.alt &&
    ((context == other.context) || (*context == *other.context)) &&
    *semanticContext == *other.semanticContext &&
    isPrecedenceFilterSuppressed() == other.isPrecedenceFilterSuppressed();
}

std::string ATNConfig::toString() const {
  return toString(true);
}

std::string ATNConfig::toString(bool showAlt) const {
  std::stringstream ss;
  ss << "(";

  ss << state->toString();
  if (showAlt) {
    ss << "," << alt;
  }
  if (context) {
    ss << ",[" << context->toString() << "]";
  }
  if (semanticContext != nullptr && semanticContext != SemanticContext::Empty::Instance) {
    ss << "," << semanticContext->toString();
  }
  if (getOuterContextDepth() > 0) {
    ss << ",up=" << getOuterContextDepth();
  }
  ss << ")";

  return ss.str();
}
