#pragma once

#include "binder/expression/expression_util.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

// LogicalExpressionsScan scans from an outer factorize table
class LogicalExpressionsScan final : public LogicalOperator {
public:
    explicit LogicalExpressionsScan(binder::expression_vector expressions)
        : LogicalOperator{LogicalOperatorType::EXPRESSIONS_SCAN},
          expressions{std::move(expressions)}, outerAccumulate{nullptr} {}

    inline void computeFactorizedSchema() override { computeSchema(); }
    inline void computeFlatSchema() override { computeSchema(); }

    inline std::string getExpressionsForPrinting() const override {
        return binder::ExpressionUtil::toString(expressions);
    }

    inline binder::expression_vector getExpressions() const { return expressions; }
    inline void setOuterAccumulate(LogicalOperator* op) { outerAccumulate = op; }
    inline LogicalOperator* getOuterAccumulate() const { return outerAccumulate; }

    inline std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalExpressionsScan>(expressions);
    }

private:
    void computeSchema();

private:
    binder::expression_vector expressions;
    LogicalOperator* outerAccumulate;
};

} // namespace planner
} // namespace lbug
