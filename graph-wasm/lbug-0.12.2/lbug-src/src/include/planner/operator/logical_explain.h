#pragma once

#include <utility>

#include "common/enums/explain_type.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalExplain final : public LogicalOperator {
    static constexpr LogicalOperatorType type_ = LogicalOperatorType::EXPLAIN;

public:
    LogicalExplain(std::shared_ptr<LogicalOperator> child, common::ExplainType explainType,
        binder::expression_vector innerResultColumns)
        : LogicalOperator{type_, std::move(child)}, explainType{explainType},
          innerResultColumns{std::move(innerResultColumns)} {}

    void computeSchema();
    void computeFactorizedSchema() override;
    void computeFlatSchema() override;

    std::string getExpressionsForPrinting() const override { return ""; }

    common::ExplainType getExplainType() const { return explainType; }

    binder::expression_vector getInnerResultColumns() const { return innerResultColumns; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalExplain>(children[0], explainType, innerResultColumns);
    }

private:
    common::ExplainType explainType;
    binder::expression_vector innerResultColumns;
};

} // namespace planner
} // namespace lbug
