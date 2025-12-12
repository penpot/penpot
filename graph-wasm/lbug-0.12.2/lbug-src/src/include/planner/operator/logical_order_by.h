#pragma once

#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalOrderBy final : public LogicalOperator {
public:
    LogicalOrderBy(binder::expression_vector expressionsToOrderBy, std::vector<bool> sortOrders,
        std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{LogicalOperatorType::ORDER_BY, std::move(child)},
          expressionsToOrderBy{std::move(expressionsToOrderBy)},
          isAscOrders{std::move(sortOrders)} {}

    f_group_pos_set getGroupsPosToFlatten();

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override;

    binder::expression_vector getExpressionsToOrderBy() const { return expressionsToOrderBy; }
    std::vector<bool> getIsAscOrders() const { return isAscOrders; }

    bool isTopK() const { return hasLimitNum(); }

    void setSkipNum(std::shared_ptr<binder::Expression> num) { skipNum = std::move(num); }
    bool hasSkipNum() const { return skipNum != nullptr; }
    std::shared_ptr<binder::Expression> getSkipNum() const { return skipNum; }

    void setLimitNum(std::shared_ptr<binder::Expression> num) { limitNum = std::move(num); }
    bool hasLimitNum() const { return limitNum != nullptr; }
    std::shared_ptr<binder::Expression> getLimitNum() const { return limitNum; }

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalOrderBy>(expressionsToOrderBy, isAscOrders, children[0]->copy());
    }

private:
    binder::expression_vector expressionsToOrderBy;
    std::vector<bool> isAscOrders;
    std::shared_ptr<binder::Expression> skipNum = nullptr;
    std::shared_ptr<binder::Expression> limitNum = nullptr;
};

} // namespace planner
} // namespace lbug
