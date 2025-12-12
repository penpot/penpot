#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalSimple : public LogicalOperator {
public:
    explicit LogicalSimple(LogicalOperatorType operatorType) : LogicalOperator{operatorType} {}
    LogicalSimple(LogicalOperatorType operatorType,
        const std::vector<std::shared_ptr<LogicalOperator>>& plans)
        : LogicalOperator{operatorType, plans} {}

    void computeFactorizedSchema() override;

    void computeFlatSchema() override;
};

} // namespace planner
} // namespace lbug
