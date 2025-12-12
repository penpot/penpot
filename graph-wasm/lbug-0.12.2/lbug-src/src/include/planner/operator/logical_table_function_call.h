#pragma once

#include "function/table/bind_data.h"
#include "function/table/table_function.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LBUG_API LogicalTableFunctionCall final : public LogicalOperator {
    static constexpr LogicalOperatorType operatorType_ = LogicalOperatorType::TABLE_FUNCTION_CALL;

public:
    LogicalTableFunctionCall(function::TableFunction tableFunc,
        std::unique_ptr<function::TableFuncBindData> bindData)
        : LogicalOperator{operatorType_}, tableFunc{std::move(tableFunc)},
          bindData{std::move(bindData)} {
        setCardinality(this->bindData->numRows);
    }

    const function::TableFunction& getTableFunc() const { return tableFunc; }
    const function::TableFuncBindData* getBindData() const { return bindData.get(); }

    void setColumnSkips(std::vector<bool> columnSkips) {
        bindData->setColumnSkips(std::move(columnSkips));
    }
    void setColumnPredicates(std::vector<storage::ColumnPredicateSet> predicates) {
        bindData->setColumnPredicates(std::move(predicates));
    }

    void computeFlatSchema() override;
    void computeFactorizedSchema() override;

    std::string getExpressionsForPrinting() const override { return tableFunc.name; }

    std::unique_ptr<LogicalOperator> copy() override {
        return std::make_unique<LogicalTableFunctionCall>(tableFunc, bindData->copy());
    }

private:
    function::TableFunction tableFunc;
    std::unique_ptr<function::TableFuncBindData> bindData;
};

} // namespace planner
} // namespace lbug
