#pragma once

#include "binder/expression/expression.h"
#include "expression_evaluator/expression_evaluator.h"
#include "processor/result/result_set_descriptor.h"

namespace lbug {
namespace processor {

class ExpressionMapper {
public:
    ExpressionMapper() = default;
    explicit ExpressionMapper(const planner::Schema* schema) : schema{schema} {}
    ExpressionMapper(const planner::Schema* schema, evaluator::ExpressionEvaluator* parent)
        : schema{schema}, parentEvaluator{parent} {}

    std::unique_ptr<evaluator::ExpressionEvaluator> getEvaluator(
        std::shared_ptr<binder::Expression> expression);
    std::unique_ptr<evaluator::ExpressionEvaluator> getConstantEvaluator(
        std::shared_ptr<binder::Expression> expression);

private:
    static std::unique_ptr<evaluator::ExpressionEvaluator> getLiteralEvaluator(
        std::shared_ptr<binder::Expression> expression);

    static std::unique_ptr<evaluator::ExpressionEvaluator> getParameterEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::unique_ptr<evaluator::ExpressionEvaluator> getReferenceEvaluator(
        std::shared_ptr<binder::Expression> expression) const;

    static std::unique_ptr<evaluator::ExpressionEvaluator> getLambdaParamEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::unique_ptr<evaluator::ExpressionEvaluator> getCaseEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::unique_ptr<evaluator::ExpressionEvaluator> getFunctionEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::unique_ptr<evaluator::ExpressionEvaluator> getNodeEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::unique_ptr<evaluator::ExpressionEvaluator> getRelEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::unique_ptr<evaluator::ExpressionEvaluator> getPathEvaluator(
        std::shared_ptr<binder::Expression> expression);

    std::vector<std::unique_ptr<evaluator::ExpressionEvaluator>> getEvaluators(
        const binder::expression_vector& expressions);

private:
    const planner::Schema* schema = nullptr;
    // TODO: comment
    evaluator::ExpressionEvaluator* parentEvaluator = nullptr;
};

} // namespace processor
} // namespace lbug
