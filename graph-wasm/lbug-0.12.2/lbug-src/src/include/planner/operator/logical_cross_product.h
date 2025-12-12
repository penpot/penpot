#pragma once

#include "common/enums/accumulate_type.h"
#include "planner/operator/logical_operator.h"
#include "planner/operator/sip/side_way_info_passing.h"

namespace lbug {
namespace planner {

class LogicalCrossProduct final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::CROSS_PRODUCT;

public:
    LogicalCrossProduct(common::AccumulateType accumulateType,
        std::shared_ptr<binder::Expression> mark, std::shared_ptr<LogicalOperator> probeChild,
        std::shared_ptr<LogicalOperator> buildChild, common::cardinality_t cardinality)
        : LogicalOperator{type_, std::move(probeChild), std::move(buildChild)},
          accumulateType{accumulateType}, mark{std::move(mark)} {
        this->cardinality = cardinality;
    }

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override { return std::string(); }

    common::AccumulateType getAccumulateType() const { return accumulateType; }
    bool hasMark() const { return mark != nullptr; }
    std::shared_ptr<binder::Expression> getMark() const { return mark; }

    SIPInfo& getSIPInfoUnsafe() { return sipInfo; }
    SIPInfo getSIPInfo() const { return sipInfo; }

    std::unique_ptr<LogicalOperator> copy() override {
        auto op = make_unique<LogicalCrossProduct>(accumulateType, mark, children[0]->copy(),
            children[1]->copy(), cardinality);
        op->sipInfo = sipInfo;
        return op;
    }

private:
    common::AccumulateType accumulateType;
    std::shared_ptr<binder::Expression> mark;
    SIPInfo sipInfo;
};

} // namespace planner
} // namespace lbug
