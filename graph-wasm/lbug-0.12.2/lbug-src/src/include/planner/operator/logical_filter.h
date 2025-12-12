#pragma once

#include "binder/expression/expression.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

struct LogicalFilterPrintInfo final : OPPrintInfo {
    std::shared_ptr<binder::Expression> expression;
    explicit LogicalFilterPrintInfo(std::shared_ptr<binder::Expression> expression)
        : expression{std::move(expression)} {}
    std::string toString() const override { return expression->toString(); }
};

class LogicalFilter final : public LogicalOperator {
public:
    LogicalFilter(std::shared_ptr<binder::Expression> expression,
        std::shared_ptr<LogicalOperator> child, common::cardinality_t cardinality = 0)
        : LogicalOperator{LogicalOperatorType::FILTER, std::move(child), cardinality},
          expression{std::move(expression)} {}

    inline void computeFactorizedSchema() override { copyChildSchema(0); }
    inline void computeFlatSchema() override { copyChildSchema(0); }

    f_group_pos_set getGroupsPosToFlatten();

    inline std::string getExpressionsForPrinting() const override { return expression->toString(); }

    inline std::shared_ptr<binder::Expression> getPredicate() const { return expression; }

    f_group_pos getGroupPosToSelect() const;

    std::unique_ptr<OPPrintInfo> getPrintInfo() const override {
        return std::make_unique<LogicalFilterPrintInfo>(expression);
    }

    inline std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalFilter>(expression, children[0]->copy(), cardinality);
    }

private:
    std::shared_ptr<binder::Expression> expression;
};

} // namespace planner
} // namespace lbug
