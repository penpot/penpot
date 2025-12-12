#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

// Serve as a dummy parent (usually root) for a set of children that doesn't have a well-defined
// parent. E.g. CREATE TABLE AS, create table & copy.
class LogicalNoop : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::NOOP;

public:
    explicit LogicalNoop(common::idx_t messageChildIdx,
        std::vector<std::shared_ptr<LogicalOperator>> children)
        : LogicalOperator{type_, {std::move(children)}}, messageChildIdx{messageChildIdx} {}

    void computeFactorizedSchema() override { createEmptySchema(); }
    void computeFlatSchema() override { createEmptySchema(); }

    common::idx_t getMessageChildIdx() const { return messageChildIdx; }

    std::string getExpressionsForPrinting() const override { return ""; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalNoop>(messageChildIdx, copyVector(children));
    }

private:
    // For create table as. Dummy sink is the last operator and should propagate return message.
    common::idx_t messageChildIdx;
};

} // namespace planner
} // namespace lbug
