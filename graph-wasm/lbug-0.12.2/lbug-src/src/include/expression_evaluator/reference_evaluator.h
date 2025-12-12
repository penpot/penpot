#pragma once

#include "expression_evaluator.h"

namespace lbug {
namespace main {
class ClientContext;
}

namespace evaluator {

class ReferenceExpressionEvaluator : public ExpressionEvaluator {
    static constexpr EvaluatorType type_ = EvaluatorType::REFERENCE;

public:
    ReferenceExpressionEvaluator(std::shared_ptr<binder::Expression> expression, bool isResultFlat,
        const processor::DataPos& dataPos)
        : ExpressionEvaluator{type_, std::move(expression), isResultFlat}, dataPos{dataPos} {}

    void evaluate() override {}

    bool selectInternal(common::SelectionVector& selVector) override;

    std::unique_ptr<ExpressionEvaluator> copy() override {
        return std::make_unique<ReferenceExpressionEvaluator>(expression, isResultFlat_, dataPos);
    }

protected:
    void resolveResultVector(const processor::ResultSet& resultSet,
        storage::MemoryManager*) override {
        resultVector = resultSet.getValueVector(dataPos);
    }

private:
    processor::DataPos dataPos;
};

} // namespace evaluator
} // namespace lbug
