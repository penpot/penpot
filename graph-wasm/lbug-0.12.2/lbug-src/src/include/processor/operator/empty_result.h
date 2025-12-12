#pragma once

#include "physical_operator.h"

namespace lbug {
namespace processor {

class EmptyResult final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::EMPTY_RESULT;

public:
    EmptyResult(physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, id, std::move(printInfo)} {}

    bool isSource() const override { return true; }

    bool getNextTuplesInternal(ExecutionContext*) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<EmptyResult>(id, printInfo->copy());
    }
};

} // namespace processor
} // namespace lbug
