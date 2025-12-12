#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace main {
struct Option;
}
namespace planner {

class LogicalStandaloneCall final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::STANDALONE_CALL;

public:
    LogicalStandaloneCall(const main::Option* option,
        std::shared_ptr<binder::Expression> optionValue)
        : LogicalOperator{type_}, option{option}, optionValue{std::move(optionValue)} {}

    const main::Option* getOption() const { return option; }
    std::shared_ptr<binder::Expression> getOptionValue() const { return optionValue; }

    std::string getExpressionsForPrinting() const override;

    void computeFlatSchema() override { createEmptySchema(); }

    void computeFactorizedSchema() override { createEmptySchema(); }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalStandaloneCall>(option, optionValue);
    }

protected:
    const main::Option* option;
    std::shared_ptr<binder::Expression> optionValue;
};

} // namespace planner
} // namespace lbug
