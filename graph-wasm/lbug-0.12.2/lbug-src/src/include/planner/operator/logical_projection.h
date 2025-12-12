#pragma once

#include "binder/expression/expression.h"
#include "binder/expression/expression_util.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LBUG_API LogicalProjection : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::PROJECTION;

public:
    LogicalProjection(binder::expression_vector expressions, std::shared_ptr<LogicalOperator> child)
        : LogicalOperator{type_, std::move(child)}, expressions{std::move(expressions)} {}

    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override {
        return binder::ExpressionUtil::toString(expressions);
    }

    binder::expression_vector getExpressionsToProject() const { return expressions; }
    void setExpressionsToProject(const binder::expression_vector& expressions) {
        this->expressions = expressions;
    }

    std::unordered_set<uint32_t> getDiscardedGroupsPos() const;

    std::unique_ptr<LogicalOperator> copy() override {
        return make_unique<LogicalProjection>(expressions, children[0]->copy());
    }

private:
    binder::expression_vector expressions;
};

} // namespace planner
} // namespace lbug
