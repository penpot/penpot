/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#include "atn/RuleStopState.h"
#include "atn/Transition.h"
#include "atn/RuleTransition.h"
#include "atn/SingletonPredictionContext.h"
#include "atn/WildcardTransition.h"
#include "atn/NotSetTransition.h"
#include "misc/IntervalSet.h"
#include "atn/ATNConfig.h"

#include "support/CPPUtils.h"

#include "atn/LL1Analyzer.h"

using namespace antlr4;
using namespace antlr4::atn;
using namespace antlrcpp;

namespace {

  struct ATNConfigHasher final {
    size_t operator()(const ATNConfig& atn_config) const {
      return atn_config.hashCode();
    }
  };

  struct ATNConfigComparer final {
    bool operator()(const ATNConfig& lhs, const ATNConfig& rhs) const {
      return lhs == rhs;
    }
  };

  class LL1AnalyzerImpl final {
  public:
    LL1AnalyzerImpl(const ATN& atn, misc::IntervalSet& look, bool seeThruPreds, bool addEOF) : _atn(atn), _look(look), _seeThruPreds(seeThruPreds), _addEOF(addEOF) {}

    /// <summary>
    /// Compute set of tokens that can follow {@code s} in the ATN in the
    /// specified {@code ctx}.
    /// <p/>
    /// If {@code ctx} is {@code null} and {@code stopState} or the end of the
    /// rule containing {@code s} is reached, <seealso cref="Token#EPSILON"/> is added to
    /// the result set. If {@code ctx} is not {@code null} and {@code addEOF} is
    /// {@code true} and {@code stopState} or the end of the outermost rule is
    /// reached, <seealso cref="Token#EOF"/> is added to the result set.
    /// </summary>
    /// <param name="s"> the ATN state. </param>
    /// <param name="stopState"> the ATN state to stop at. This can be a
    /// <seealso cref="BlockEndState"/> to detect epsilon paths through a closure. </param>
    /// <param name="ctx"> The outer context, or {@code null} if the outer context should
    /// not be used. </param>
    /// <param name="look"> The result lookahead set. </param>
    /// <param name="lookBusy"> A set used for preventing epsilon closures in the ATN
    /// from causing a stack overflow. Outside code should pass
    /// {@code new HashSet<ATNConfig>} for this argument. </param>
    /// <param name="calledRuleStack"> A set used for preventing left recursion in the
    /// ATN from causing a stack overflow. Outside code should pass
    /// {@code new BitSet()} for this argument. </param>
    /// <param name="seeThruPreds"> {@code true} to true semantic predicates as
    /// implicitly {@code true} and "see through them", otherwise {@code false}
    /// to treat semantic predicates as opaque and add <seealso cref="#HIT_PRED"/> to the
    /// result if one is encountered. </param>
    /// <param name="addEOF"> Add <seealso cref="Token#EOF"/> to the result if the end of the
    /// outermost context is reached. This parameter has no effect if {@code ctx}
    /// is {@code null}. </param>
    void LOOK(ATNState *s, ATNState *stopState, Ref<const PredictionContext> const& ctx) {
      if (!_lookBusy.insert(ATNConfig(s, 0, ctx)).second) {
        return;
      }

      // ml: s can never be null, hence no need to check if stopState is != null.
      if (s == stopState) {
        if (ctx == nullptr) {
          _look.add(Token::EPSILON);
          return;
        } else if (ctx->isEmpty() && _addEOF) {
          _look.add(Token::EOF);
          return;
        }
      }

      if (s->getStateType() == ATNStateType::RULE_STOP) {
        if (ctx == nullptr) {
          _look.add(Token::EPSILON);
          return;
        } else if (ctx->isEmpty() && _addEOF) {
          _look.add(Token::EOF);
          return;
        }

        if (ctx != PredictionContext::EMPTY) {
          bool removed = _calledRuleStack.test(s->ruleIndex);
          _calledRuleStack[s->ruleIndex] = false;
          // run thru all possible stack tops in ctx
          for (size_t i = 0; i < ctx->size(); i++) {
            ATNState *returnState = _atn.states[ctx->getReturnState(i)];
            LOOK(returnState, stopState, ctx->getParent(i));
          }
          if (removed) {
            _calledRuleStack.set(s->ruleIndex);
          }
          return;
        }
      }

      size_t n = s->transitions.size();
      for (size_t i = 0; i < n; i++) {
        const Transition *t = s->transitions[i].get();
        const auto tType = t->getTransitionType();

        if (tType == TransitionType::RULE) {
          if (_calledRuleStack[(static_cast<const RuleTransition*>(t))->target->ruleIndex]) {
            continue;
          }

          Ref<const PredictionContext> newContext = SingletonPredictionContext::create(ctx, (static_cast<const RuleTransition*>(t))->followState->stateNumber);

          _calledRuleStack.set((static_cast<const RuleTransition*>(t))->target->ruleIndex);
          LOOK(t->target, stopState, newContext);
          _calledRuleStack[(static_cast<const RuleTransition*>(t))->target->ruleIndex] = false;

        } else if (tType == TransitionType::PREDICATE || tType == TransitionType::PRECEDENCE) {
          if (_seeThruPreds) {
            LOOK(t->target, stopState, ctx);
          } else {
            _look.add(LL1Analyzer::HIT_PRED);
          }
        } else if (t->isEpsilon()) {
          LOOK(t->target, stopState, ctx);
        } else if (tType == TransitionType::WILDCARD) {
          _look.addAll(misc::IntervalSet::of(Token::MIN_USER_TOKEN_TYPE, static_cast<ssize_t>(_atn.maxTokenType)));
        } else {
          misc::IntervalSet set = t->label();
          if (!set.isEmpty()) {
            if (tType == TransitionType::NOT_SET) {
              set = set.complement(misc::IntervalSet::of(Token::MIN_USER_TOKEN_TYPE, static_cast<ssize_t>(_atn.maxTokenType)));
            }
            _look.addAll(set);
          }
        }
      }
    }

  private:
    const ATN& _atn;
    misc::IntervalSet& _look;
    antlrcpp::BitSet _calledRuleStack;
    std::unordered_set<ATNConfig, ATNConfigHasher, ATNConfigComparer> _lookBusy;
    bool _seeThruPreds;
    bool _addEOF;
  };

}

std::vector<misc::IntervalSet> LL1Analyzer::getDecisionLookahead(ATNState *s) const {
  std::vector<misc::IntervalSet> look;

  if (s == nullptr) {
    return look;
  }

  look.resize(s->transitions.size()); // Fills all interval sets with defaults.
  for (size_t alt = 0; alt < s->transitions.size(); alt++) {
    LL1AnalyzerImpl impl(_atn, look[alt], false, false);
    impl.LOOK(s->transitions[alt]->target, nullptr, PredictionContext::EMPTY);
    // Wipe out lookahead for this alternative if we found nothing
    // or we had a predicate when we !seeThruPreds
    if (look[alt].size() == 0 || look[alt].contains(LL1Analyzer::HIT_PRED)) {
      look[alt].clear();
    }
  }
  return look;
}

misc::IntervalSet LL1Analyzer::LOOK(ATNState *s, RuleContext *ctx) const {
  return LOOK(s, nullptr, ctx);
}

misc::IntervalSet LL1Analyzer::LOOK(ATNState *s, ATNState *stopState, RuleContext *ctx) const {
  Ref<const PredictionContext> lookContext = ctx != nullptr ? PredictionContext::fromRuleContext(_atn, ctx) : nullptr;
  misc::IntervalSet r;
  LL1AnalyzerImpl impl(_atn, r, true, true);
  impl.LOOK(s, stopState, lookContext);
  return r;
}
