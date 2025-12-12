/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include <functional>
#include <unordered_set>

#include "misc/MurmurHash.h"
#include "support/Casts.h"
#include "support/CPPUtils.h"
#include "support/Arrays.h"

#include "SemanticContext.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlrcpp;

namespace {

  struct SemanticContextHasher final {
    size_t operator()(const SemanticContext *semanticContext) const {
      return semanticContext->hashCode();
    }
  };

  struct SemanticContextComparer final {
    bool operator()(const SemanticContext *lhs, const SemanticContext *rhs) const {
      return *lhs == *rhs;
    }
  };

  template <typename Comparer>
  void insertSemanticContext(const Ref<const SemanticContext> &semanticContext,
                             std::unordered_set<const SemanticContext*, SemanticContextHasher, SemanticContextComparer> &operandSet,
                             std::vector<Ref<const SemanticContext>> &operandList,
                             Ref<const SemanticContext::PrecedencePredicate> &precedencePredicate,
                             Comparer comparer) {
    if (semanticContext != nullptr) {
      if (semanticContext->getContextType() == SemanticContextType::PRECEDENCE) {
        if (precedencePredicate == nullptr || comparer(downCast<const SemanticContext::PrecedencePredicate*>(semanticContext.get())->precedence, precedencePredicate->precedence)) {
          precedencePredicate = std::static_pointer_cast<const SemanticContext::PrecedencePredicate>(semanticContext);
        }
      } else {
        auto [existing, inserted] = operandSet.insert(semanticContext.get());
        if (inserted) {
          operandList.push_back(semanticContext);
        }
      }
    }
  }

  template <typename Comparer>
  void insertSemanticContext(Ref<const SemanticContext> &&semanticContext,
                             std::unordered_set<const SemanticContext*, SemanticContextHasher, SemanticContextComparer> &operandSet,
                             std::vector<Ref<const SemanticContext>> &operandList,
                             Ref<const SemanticContext::PrecedencePredicate> &precedencePredicate,
                             Comparer comparer) {
    if (semanticContext != nullptr) {
      if (semanticContext->getContextType() == SemanticContextType::PRECEDENCE) {
        if (precedencePredicate == nullptr || comparer(downCast<const SemanticContext::PrecedencePredicate*>(semanticContext.get())->precedence, precedencePredicate->precedence)) {
          precedencePredicate = std::static_pointer_cast<const SemanticContext::PrecedencePredicate>(std::move(semanticContext));
        }
      } else {
        auto [existing, inserted] = operandSet.insert(semanticContext.get());
        if (inserted) {
          operandList.push_back(std::move(semanticContext));
        }
      }
    }
  }

  size_t predictOperandCapacity(const Ref<const SemanticContext> &x) {
    switch (x->getContextType()) {
      case SemanticContextType::AND:
        return downCast<const SemanticContext::AND&>(*x).getOperands().size();
      case SemanticContextType::OR:
        return downCast<const SemanticContext::OR&>(*x).getOperands().size();
      default:
        return 1;
    }
  }

  size_t predictOperandCapacity(const Ref<const SemanticContext> &a, const Ref<const SemanticContext> &b) {
    return predictOperandCapacity(a) + predictOperandCapacity(b);
  }

}

//------------------ Predicate -----------------------------------------------------------------------------------------

SemanticContext::Predicate::Predicate(size_t ruleIndex, size_t predIndex, bool isCtxDependent)
    : SemanticContext(SemanticContextType::PREDICATE), ruleIndex(ruleIndex), predIndex(predIndex), isCtxDependent(isCtxDependent) {}

bool SemanticContext::Predicate::eval(Recognizer *parser, RuleContext *parserCallStack) const {
  RuleContext *localctx = nullptr;
  if (isCtxDependent) {
    localctx = parserCallStack;
  }
  return parser->sempred(localctx, ruleIndex, predIndex);
}

size_t SemanticContext::Predicate::hashCode() const {
  size_t hashCode = misc::MurmurHash::initialize();
  hashCode = misc::MurmurHash::update(hashCode, static_cast<size_t>(getContextType()));
  hashCode = misc::MurmurHash::update(hashCode, ruleIndex);
  hashCode = misc::MurmurHash::update(hashCode, predIndex);
  hashCode = misc::MurmurHash::update(hashCode, isCtxDependent ? 1 : 0);
  hashCode = misc::MurmurHash::finish(hashCode, 4);
  return hashCode;
}

bool SemanticContext::Predicate::equals(const SemanticContext &other) const {
  if (this == &other) {
    return true;
  }
  if (getContextType() != other.getContextType()) {
    return false;
  }
  const Predicate &p = downCast<const Predicate&>(other);
  return ruleIndex == p.ruleIndex && predIndex == p.predIndex && isCtxDependent == p.isCtxDependent;
}

std::string SemanticContext::Predicate::toString() const {
  return std::string("{") + std::to_string(ruleIndex) + std::string(":") + std::to_string(predIndex) + std::string("}?");
}

//------------------ PrecedencePredicate -------------------------------------------------------------------------------

SemanticContext::PrecedencePredicate::PrecedencePredicate(int precedence) : SemanticContext(SemanticContextType::PRECEDENCE), precedence(precedence) {}

bool SemanticContext::PrecedencePredicate::eval(Recognizer *parser, RuleContext *parserCallStack) const {
  return parser->precpred(parserCallStack, precedence);
}

Ref<const SemanticContext> SemanticContext::PrecedencePredicate::evalPrecedence(Recognizer *parser,
  RuleContext *parserCallStack) const {
  if (parser->precpred(parserCallStack, precedence)) {
    return SemanticContext::Empty::Instance;
  }
  return nullptr;
}

size_t SemanticContext::PrecedencePredicate::hashCode() const {
  size_t hashCode = misc::MurmurHash::initialize();
  hashCode = misc::MurmurHash::update(hashCode, static_cast<size_t>(getContextType()));
  hashCode = misc::MurmurHash::update(hashCode, static_cast<size_t>(precedence));
  return misc::MurmurHash::finish(hashCode, 2);
}

bool SemanticContext::PrecedencePredicate::equals(const SemanticContext &other) const {
  if (this == &other) {
    return true;
  }
  if (getContextType() != other.getContextType()) {
    return false;
  }
  const PrecedencePredicate &predicate = downCast<const PrecedencePredicate&>(other);
  return precedence == predicate.precedence;
}

std::string SemanticContext::PrecedencePredicate::toString() const {
  return "{" + std::to_string(precedence) + ">=prec}?";
}

//------------------ AND -----------------------------------------------------------------------------------------------

SemanticContext::AND::AND(Ref<const SemanticContext> a, Ref<const SemanticContext> b) : Operator(SemanticContextType::AND) {
  std::unordered_set<const SemanticContext*, SemanticContextHasher, SemanticContextComparer> operands;
  Ref<const SemanticContext::PrecedencePredicate> precedencePredicate;

  _opnds.reserve(predictOperandCapacity(a, b) + 1);

  if (a->getContextType() == SemanticContextType::AND) {
    for (const auto &operand : downCast<const AND*>(a.get())->getOperands()) {
      insertSemanticContext(operand, operands, _opnds, precedencePredicate, std::less<int>{});
    }
  } else {
    insertSemanticContext(std::move(a), operands, _opnds, precedencePredicate, std::less<int>{});
  }

  if (b->getContextType() == SemanticContextType::AND) {
    for (const auto &operand : downCast<const AND*>(b.get())->getOperands()) {
      insertSemanticContext(operand, operands, _opnds, precedencePredicate, std::less<int>{});
    }
  } else {
    insertSemanticContext(std::move(b), operands, _opnds, precedencePredicate, std::less<int>{});
  }

  if (precedencePredicate != nullptr) {
    // interested in the transition with the lowest precedence
    auto [existing, inserted] = operands.insert(precedencePredicate.get());
    if (inserted) {
      _opnds.push_back(std::move(precedencePredicate));
    }
  }
}

const std::vector<Ref<const SemanticContext>>& SemanticContext::AND::getOperands() const {
  return _opnds;
}

bool SemanticContext::AND::equals(const SemanticContext &other) const {
  if (this == &other) {
    return true;
  }
  if (getContextType() != other.getContextType()) {
    return false;
  }
  const AND &context = downCast<const AND&>(other);
  return Arrays::equals(getOperands(), context.getOperands());
}

size_t SemanticContext::AND::hashCode() const {
  size_t hash = misc::MurmurHash::initialize();
  hash = misc::MurmurHash::update(hash, static_cast<size_t>(getContextType()));
  return misc::MurmurHash::hashCode(getOperands(), hash);
}

bool SemanticContext::AND::eval(Recognizer *parser, RuleContext *parserCallStack) const {
  for (const auto &opnd : getOperands()) {
    if (!opnd->eval(parser, parserCallStack)) {
      return false;
    }
  }
  return true;
}

Ref<const SemanticContext> SemanticContext::AND::evalPrecedence(Recognizer *parser, RuleContext *parserCallStack) const {
  bool differs = false;
  std::vector<Ref<const SemanticContext>> operands;
  for (const auto &context : getOperands()) {
    auto evaluated = context->evalPrecedence(parser, parserCallStack);
    differs |= (evaluated != context);
    if (evaluated == nullptr) {
      // The AND context is false if any element is false.
      return nullptr;
    }
    if (evaluated != Empty::Instance) {
      // Reduce the result by skipping true elements.
      operands.push_back(std::move(evaluated));
    }
  }

  if (!differs) {
    return shared_from_this();
  }

  if (operands.empty()) {
    // All elements were true, so the AND context is true.
    return Empty::Instance;
  }

  Ref<const SemanticContext> result = std::move(operands[0]);
  for (size_t i = 1; i < operands.size(); ++i) {
    result = SemanticContext::And(std::move(result), std::move(operands[i]));
  }

  return result;
}

std::string SemanticContext::AND::toString() const {
  std::string tmp;
  for (const auto &var : getOperands()) {
    tmp += var->toString() + " && ";
  }
  return tmp;
}

//------------------ OR ------------------------------------------------------------------------------------------------

SemanticContext::OR::OR(Ref<const SemanticContext> a, Ref<const SemanticContext> b) : Operator(SemanticContextType::OR) {
  std::unordered_set<const SemanticContext*, SemanticContextHasher, SemanticContextComparer> operands;
  Ref<const SemanticContext::PrecedencePredicate> precedencePredicate;

  _opnds.reserve(predictOperandCapacity(a, b) + 1);

  if (a->getContextType() == SemanticContextType::OR) {
    for (const auto &operand : downCast<const OR*>(a.get())->getOperands()) {
      insertSemanticContext(operand, operands, _opnds, precedencePredicate, std::greater<int>{});
    }
  } else {
    insertSemanticContext(std::move(a), operands, _opnds, precedencePredicate, std::greater<int>{});
  }

  if (b->getContextType() == SemanticContextType::OR) {
    for (const auto &operand : downCast<const OR*>(b.get())->getOperands()) {
      insertSemanticContext(operand, operands, _opnds, precedencePredicate, std::greater<int>{});
    }
  } else {
    insertSemanticContext(std::move(b), operands, _opnds, precedencePredicate, std::greater<int>{});
  }

  if (precedencePredicate != nullptr) {
    // interested in the transition with the highest precedence
    auto [existing, inserted] = operands.insert(precedencePredicate.get());
    if (inserted) {
      _opnds.push_back(std::move(precedencePredicate));
    }
  }
}

const std::vector<Ref<const SemanticContext>>& SemanticContext::OR::getOperands() const {
  return _opnds;
}

bool SemanticContext::OR::equals(const SemanticContext &other) const {
  if (this == &other) {
    return true;
  }
  if (getContextType() != other.getContextType()) {
    return false;
  }
  const OR &context = downCast<const OR&>(other);
  return Arrays::equals(getOperands(), context.getOperands());
}

size_t SemanticContext::OR::hashCode() const {
  size_t hash = misc::MurmurHash::initialize();
  hash = misc::MurmurHash::update(hash, static_cast<size_t>(getContextType()));
  return misc::MurmurHash::hashCode(getOperands(), hash);
}

bool SemanticContext::OR::eval(Recognizer *parser, RuleContext *parserCallStack) const {
  for (const auto &opnd : getOperands()) {
    if (opnd->eval(parser, parserCallStack)) {
      return true;
    }
  }
  return false;
}

Ref<const SemanticContext> SemanticContext::OR::evalPrecedence(Recognizer *parser, RuleContext *parserCallStack) const {
  bool differs = false;
  std::vector<Ref<const SemanticContext>> operands;
  for (const auto &context : getOperands()) {
    auto evaluated = context->evalPrecedence(parser, parserCallStack);
    differs |= (evaluated != context);
    if (evaluated == Empty::Instance) {
      // The OR context is true if any element is true.
      return Empty::Instance;
    }
    if (evaluated != nullptr) {
      // Reduce the result by skipping false elements.
      operands.push_back(std::move(evaluated));
    }
  }

  if (!differs) {
    return shared_from_this();
  }

  if (operands.empty()) {
    // All elements were false, so the OR context is false.
    return nullptr;
  }

  Ref<const SemanticContext> result = std::move(operands[0]);
  for (size_t i = 1; i < operands.size(); ++i) {
    result = SemanticContext::Or(std::move(result), std::move(operands[i]));
  }

  return result;
}

std::string SemanticContext::OR::toString() const {
  std::string tmp;
  for(const auto &var : getOperands()) {
    tmp += var->toString() + " || ";
  }
  return tmp;
}

//------------------ SemanticContext -----------------------------------------------------------------------------------

const Ref<const SemanticContext> SemanticContext::Empty::Instance = std::make_shared<Predicate>(INVALID_INDEX, INVALID_INDEX, false);

Ref<const SemanticContext> SemanticContext::evalPrecedence(Recognizer * /*parser*/, RuleContext * /*parserCallStack*/) const {
  return shared_from_this();
}

Ref<const SemanticContext> SemanticContext::And(Ref<const SemanticContext> a, Ref<const SemanticContext> b) {
  if (!a || a == Empty::Instance) {
    return b;
  }

  if (!b || b == Empty::Instance) {
    return a;
  }

  Ref<AND> result = std::make_shared<AND>(std::move(a), std::move(b));
  if (result->getOperands().size() == 1) {
    return result->getOperands()[0];
  }

  return result;
}

Ref<const SemanticContext> SemanticContext::Or(Ref<const SemanticContext> a, Ref<const SemanticContext> b) {
  if (!a) {
    return b;
  }
  if (!b) {
    return a;
  }

  if (a == Empty::Instance || b == Empty::Instance) {
    return Empty::Instance;
  }

  Ref<OR> result = std::make_shared<OR>(std::move(a), std::move(b));
  if (result->getOperands().size() == 1) {
    return result->getOperands()[0];
  }

  return result;
}
