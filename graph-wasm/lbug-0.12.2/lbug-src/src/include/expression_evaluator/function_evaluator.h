#pragma once

#include "expression_evaluator.h"
#include "function/scalar_function.h"

namespace lbug {
namespace evaluator {

class FunctionExpressionEvaluator : public ExpressionEvaluator {
    static constexpr EvaluatorType type_ = EvaluatorType::FUNCTION;

public:
    FunctionExpressionEvaluator(std::shared_ptr<binder::Expression> expression,
        std::vector<std::unique_ptr<ExpressionEvaluator>> children);

    void evaluate() override;
    void evaluate(common::sel_t count) override;

    bool selectInternal(common::SelectionVector& selVector) override;

    std::unique_ptr<ExpressionEvaluator> copy() override {
        return std::make_unique<FunctionExpressionEvaluator>(expression, copyVector(children));
    }

protected:
    void resolveResultVector(const processor::ResultSet& resultSet,
        storage::MemoryManager* memoryManager) override;

    void runExecFunc(void* dataPtr = nullptr);

private:
    std::vector<std::shared_ptr<common::ValueVector>> parameters;
    std::unique_ptr<function::ScalarFunction> function;
    std::unique_ptr<function::FunctionBindData> bindData;
};

} // namespace evaluator
} // namespace lbug
