#pragma once

#include "binder/expression/expression.h"
#include "processor/operator/sink.h"
#include "processor/result/result_set.h"
#include "sort_state.h"

namespace lbug {
namespace processor {

struct OrderByPrintInfo final : OPPrintInfo {
    binder::expression_vector keys;
    binder::expression_vector payloads;

    OrderByPrintInfo(binder::expression_vector keys, binder::expression_vector payloads)
        : keys(std::move(keys)), payloads(std::move(payloads)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<OrderByPrintInfo>(new OrderByPrintInfo(*this));
    }

private:
    OrderByPrintInfo(const OrderByPrintInfo& other)
        : OPPrintInfo(other), keys(other.keys), payloads(other.payloads) {}
};

class OrderBy final : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::ORDER_BY;

public:
    OrderBy(OrderByDataInfo info, std::shared_ptr<SortSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, std::move(child), id, std::move(printInfo)}, info{std::move(info)},
          sharedState{std::move(sharedState)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) final;

    void executeInternal(ExecutionContext* context) override;

    void finalize(ExecutionContext* /*context*/) override {
        // TODO(Ziyi): we always call lookup function on the first factorizedTable in sharedState
        // and that lookup function may read tuples in other factorizedTable, So we need to combine
        // hasNoNullGuarantee with other factorizedTables. This is not a good way to solve this
        // problem, and should be changed later.
        sharedState->combineFTHasNoNullGuarantee();
    }

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<OrderBy>(info.copy(), sharedState, children[0]->copy(), id,
            printInfo->copy());
    }

private:
    void initGlobalStateInternal(ExecutionContext* context) override;

private:
    OrderByDataInfo info;
    SortLocalState localState;
    std::shared_ptr<SortSharedState> sharedState;
    std::vector<common::ValueVector*> orderByVectors;
    std::vector<common::ValueVector*> payloadVectors;
};

} // namespace processor
} // namespace lbug
