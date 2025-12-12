#pragma once

#include "function/gds/rec_joins.h"
#include "planner/operator/logical_operator.h"

namespace lbug {
namespace planner {

class LogicalRecursiveExtend final : public LogicalOperator {
    static constexpr LogicalOperatorType operatorType_ = LogicalOperatorType::RECURSIVE_EXTEND;

public:
    LogicalRecursiveExtend(std::unique_ptr<function::RJAlgorithm> function,
        const function::RJBindData& bindData, binder::expression_vector resultColumns)
        : LogicalOperator{operatorType_}, function{std::move(function)}, bindData{bindData},
          resultColumns{std::move(resultColumns)}, limitNum{common::INVALID_LIMIT} {}

    void computeFlatSchema() override;
    void computeFactorizedSchema() override;

    void setFunction(std::unique_ptr<function::RJAlgorithm> func) { function = std::move(func); }
    const function::RJAlgorithm& getFunction() const { return *function; }

    const function::RJBindData& getBindData() const { return bindData; }
    function::RJBindData& getBindDataUnsafe() { return bindData; }

    void setResultColumns(binder::expression_vector exprs) { resultColumns = std::move(exprs); }
    binder::expression_vector getResultColumns() const { return resultColumns; }

    void setLimitNum(common::offset_t num) { limitNum = num; }
    common::offset_t getLimitNum() const { return limitNum; }

    bool hasInputNodeMask() const { return hasInputNodeMask_; }
    void setInputNodeMask() { hasInputNodeMask_ = true; }

    bool hasOutputNodeMask() const { return hasOutputNodeMask_; }
    void setOutputNodeMask() { hasOutputNodeMask_ = true; }

    bool hasNodePredicate() const { return !children.empty(); }

    std::string getExpressionsForPrinting() const override { return function->getFunctionName(); }

    std::unique_ptr<LogicalOperator> copy() override {
        auto result =
            std::make_unique<LogicalRecursiveExtend>(function->copy(), bindData, resultColumns);
        result->limitNum = limitNum;
        result->hasInputNodeMask_ = hasInputNodeMask_;
        result->hasOutputNodeMask_ = hasOutputNodeMask_;
        return result;
    }

private:
    std::unique_ptr<function::RJAlgorithm> function;
    function::RJBindData bindData;
    binder::expression_vector resultColumns;

    common::offset_t limitNum; // TODO: remove this once recursive extend is pipelined.

    bool hasInputNodeMask_ = false;
    bool hasOutputNodeMask_ = false;
};

} // namespace planner
} // namespace lbug
