/* Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

#pragma once

#include "atn/PredictionContext.h"

namespace antlr4 {
namespace atn {

  class ANTLR4CPP_PUBLIC SingletonPredictionContext final : public PredictionContext {
  public:
    static bool is(const PredictionContext &predictionContext) { return predictionContext.getContextType() == PredictionContextType::SINGLETON; }

    static bool is(const PredictionContext *predictionContext) { return predictionContext != nullptr && is(*predictionContext); }

    static Ref<const SingletonPredictionContext> create(Ref<const PredictionContext> parent, size_t returnState);

    // Usually a parent is linked via a weak ptr. Not so here as we have kinda reverse reference chain.
    // There are no child contexts stored here and often the parent context is left dangling when it's
    // owning ATNState is released. In order to avoid having this context released as well (leaving all other contexts
    // which got this one as parent with a null reference) we use a shared_ptr here instead, to keep those left alone
    // parent contexts alive.
    const Ref<const PredictionContext> parent;
    const size_t returnState;

    SingletonPredictionContext(Ref<const PredictionContext> parent, size_t returnState);

    bool isEmpty() const override;
    size_t size() const override;
    const Ref<const PredictionContext>& getParent(size_t index) const override;
    size_t getReturnState(size_t index) const override;
    bool equals(const PredictionContext &other) const override;
    std::string toString() const override;

  protected:
    size_t hashCodeImpl() const override;
  };

} // namespace atn
} // namespace antlr4
