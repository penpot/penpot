#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalDummySink final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::DUMMY_SINK;

public:
    explicit LogicalDummySink(std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, {std::move(child)}} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override { return ""; }
    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalDummySink>(children[0]->copy());
    }
};

} // namespace planner
} // namespace lbug
