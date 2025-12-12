#pragma once

#include "function/table/bind_data.h"
#include "function/table/table_function.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct TableFunctionCallInfo {
    function::TableFunction function{};
    std::unique_ptr<function::TableFuncBindData> bindData;
    std::vector<DataPos> outPosV;

    TableFunctionCallInfo() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(TableFunctionCallInfo);

private:
    TableFunctionCallInfo(const TableFunctionCallInfo& other) {
        function = other.function;
        bindData = other.bindData->copy();
        outPosV = other.outPosV;
    }
};

struct TableFunctionCallPrintInfo final : OPPrintInfo {
    std::string funcName;
    binder::expression_vector exprs;

    explicit TableFunctionCallPrintInfo(std::string funcName, binder::expression_vector exprs)
        : funcName(std::move(funcName)), exprs(std::move(exprs)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<TableFunctionCallPrintInfo>(new TableFunctionCallPrintInfo(*this));
    }

private:
    TableFunctionCallPrintInfo(const TableFunctionCallPrintInfo& other)
        : OPPrintInfo(other), funcName(other.funcName), exprs(other.exprs) {}
};

class LBUG_API TableFunctionCall final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::TABLE_FUNCTION_CALL;

public:
    TableFunctionCall(TableFunctionCallInfo info,
        std::shared_ptr<function::TableFuncSharedState> sharedState, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, id, std::move(printInfo)}, info{std::move(info)},
          sharedState{std::move(sharedState)} {}

    const TableFunctionCallInfo& getInfo() const { return info; }
    std::shared_ptr<function::TableFuncSharedState> getSharedState() const { return sharedState; }

    bool isSource() const override { return true; }

    bool isParallel() const override { return info.function.canParallelFunc(); }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    void finalizeInternal(ExecutionContext* context) override;

    double getProgress(ExecutionContext* context) const override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<TableFunctionCall>(info.copy(), sharedState, id, printInfo->copy());
    }

private:
    TableFunctionCallInfo info;
    std::shared_ptr<function::TableFuncSharedState> sharedState;
    std::unique_ptr<function::TableFuncLocalState> localState = nullptr;
    std::unique_ptr<function::TableFuncInput> funcInput = nullptr;
    std::unique_ptr<function::TableFuncOutput> funcOutput = nullptr;
};

} // namespace processor
} // namespace lbug
