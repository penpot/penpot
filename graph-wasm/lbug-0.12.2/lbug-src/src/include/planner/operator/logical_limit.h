#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalLimit final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::LIMIT;

public:
    LogicalLimit(std::shared_ptr<binder::Expression> skipNum,
        std::shared_ptr<binder::Expression> limitNum, std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, skipNum{std::move(skipNum)},
          limitNum{std::move(limitNum)} {}

    f_group_pos_set getGroupsPosToFlatten();

    void computeFactorizedSchema() override { copyChildSchema(0); }
    void computeFlatSchema() override { copyChildSchema(0); }

    std::string getExpressionsForPrinting() const override;

    bool hasSkipNum() const { return skipNum != nullptr; }
    std::shared_ptr<binder::Expression> getSkipNum() const { return skipNum; }

    bool hasLimitNum() const { return limitNum != nullptr; }
    std::shared_ptr<binder::Expression> getLimitNum() const { return limitNum; }

    f_group_pos getGroupPosToSelect() const;

    std::unordered_set<f_group_pos> getGroupsPosToLimit() const {
        return schema->getGroupsPosInScope();
    }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalLimit>(skipNum, limitNum, children[0]->copy());
    }

private:
    std::shared_ptr<binder::Expression> skipNum;
    std::shared_ptr<binder::Expression> limitNum;
};

} // namespace planner
} // namespace lbug
