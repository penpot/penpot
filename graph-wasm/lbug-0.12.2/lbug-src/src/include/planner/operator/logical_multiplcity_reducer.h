#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalMultiplicityReducer final : public LogicalOperator {
public:
    explicit LogicalMultiplicityReducer(std::shared_ptr<LogicalOperator> child)
        : LogicalOperator(LogicalOperatorType::MULTIPLICITY_REDUCER, std::move(child)) {}

    inline void computeFactorizedSchema() override { copyChildSchema(0); }
    inline void computeFlatSchema() override { copyChildSchema(0); }

    inline std::string getExpressionsForPrinting() const override { return std::string(); }

    inline std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalMultiplicityReducer>(children[0]->copy());
    }
};

} // namespace planner
} // namespace lbug
