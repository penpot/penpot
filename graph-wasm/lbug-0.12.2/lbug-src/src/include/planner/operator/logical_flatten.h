#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalFlatten final : public LogicalOperator {
public:
    LogicalFlatten(f_group_pos groupPos, std::shared_ptr<LogicalOperator> child,
        common::cardinality_t cardinality)
        : LogicalOperator{LogicalOperatorType::FLATTEN, std::move(child), cardinality},
          groupPos{groupPos} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    inline std::string getExpressionsForPrinting() const override { return std::string{}; }

    inline f_group_pos getGroupPos() const { return groupPos; }

    inline std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalFlatten>(groupPos, children[0]->copy(), cardinality);
    }

private:
    f_group_pos groupPos;
};

} // namespace planner
} // namespace lbug
