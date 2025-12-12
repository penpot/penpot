#pragma once

#include "function/gds/rec_joins.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct RecursiveExtendPrintInfo final : OPPrintInfo {
    std::string funcName;

    explicit RecursiveExtendPrintInfo(std::string funcName) : funcName{std::move(funcName)} {}

    std::string toString() const override { return funcName; }

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<RecursiveExtendPrintInfo>(new RecursiveExtendPrintInfo(*this));
    }

private:
    RecursiveExtendPrintInfo(const RecursiveExtendPrintInfo& other)
        : OPPrintInfo{other}, funcName{other.funcName} {}
};

class RecursiveExtend : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::RECURSIVE_EXTEND;

public:
    RecursiveExtend(std::unique_ptr<function::RJAlgorithm> function, function::RJBindData bindData,
        std::shared_ptr<RecursiveExtendSharedState> sharedState, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, id, std::move(printInfo)}, function{std::move(function)}, bindData{bindData},
          sharedState{std::move(sharedState)} {}

    std::shared_ptr<RecursiveExtendSharedState> getSharedState() const { return sharedState; }

    bool isSource() const override { return true; }

    bool isParallel() const override { return false; }

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<RecursiveExtend>(function->copy(), bindData, sharedState, id,
            printInfo->copy());
    }

private:
    std::unique_ptr<function::RJAlgorithm> function;
    function::RJBindData bindData;
    std::shared_ptr<RecursiveExtendSharedState> sharedState;
};

} // namespace processor
} // namespace lbug
