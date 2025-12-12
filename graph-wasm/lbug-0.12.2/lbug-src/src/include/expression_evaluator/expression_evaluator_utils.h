#pragma once

#include "binder/expression/expression.h"
#include "common/types/value/value.h"

namespace lbug {
namespace evaluator {

struct ExpressionEvaluatorUtils {
    static LBUG_API common::Value evaluateConstantExpression(
        std::shared_ptr<binder::Expression> expression, main::ClientContext* clientContext);
};

} // namespace evaluator
} // namespace lbug
