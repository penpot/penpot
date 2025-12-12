#pragma once

#include "expression_evaluator/expression_evaluator.h"
#include "processor/operator/physical_operator.h"
#include "processor/result/result_set.h"

namespace lbug {
namespace processor {

struct UnwindPrintInfo final : OPPrintInfo {
    std::shared_ptr<binder::Expression> inExpression;
    std::shared_ptr<binder::Expression> outExpression;

    UnwindPrintInfo(std::shared_ptr<binder::Expression> inExpression,
        std::shared_ptr<binder::Expression> outExpression)
        : inExpression(std::move(inExpression)), outExpression(std::move(outExpression)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<UnwindPrintInfo>(new UnwindPrintInfo(*this));
    }

private:
    UnwindPrintInfo(const UnwindPrintInfo& other)
        : OPPrintInfo(other), inExpression(other.inExpression), outExpression(other.outExpression) {
    }
};

class Unwind : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::UNWIND;

public:
    Unwind(DataPos outDataPos, DataPos idPos,
        std::unique_ptr<evaluator::ExpressionEvaluator> expressionEvaluator,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          outDataPos{outDataPos}, idPos(idPos), expressionEvaluator{std::move(expressionEvaluator)},
          startIndex{0u} {}

    bool getNextTuplesInternal(ExecutionContext* context) override;

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<Unwind>(outDataPos, idPos, expressionEvaluator->copy(),
            children[0]->copy(), id, printInfo->copy());
    }

private:
    bool hasMoreToRead() const;
    void copyTuplesToOutVector(uint64_t startPos, uint64_t endPos) const;

    DataPos outDataPos;
    DataPos idPos;

    std::unique_ptr<evaluator::ExpressionEvaluator> expressionEvaluator;
    std::shared_ptr<common::ValueVector> outValueVector;
    common::ValueVector* idVector = nullptr;
    uint32_t startIndex;
    common::list_entry_t listEntry;
};

} // namespace processor
} // namespace lbug
