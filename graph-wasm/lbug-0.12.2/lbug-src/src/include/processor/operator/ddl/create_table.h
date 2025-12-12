#pragma once

#include "binder/ddl/bound_create_table_info.h"
#include "processor/operator/sink.h"

namespace lbug {
namespace processor {

struct CreateTableSharedState {
    bool tableCreated = false;
};

class CreateTable final : public SimpleSink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::CREATE_TABLE;

public:
    CreateTable(binder::BoundCreateTableInfo info, std::shared_ptr<FactorizedTable> messageTable,
        std::shared_ptr<CreateTableSharedState> sharedState, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : SimpleSink{type_, std::move(messageTable), id, std::move(printInfo)},
          info{std::move(info)}, sharedState{std::move(sharedState)} {}

    void executeInternal(ExecutionContext* context) override;

    bool terminate() const override {
        // If table is not created, meaning table already exists. Then subsequent copy tasks should
        // not be executed.
        return !sharedState->tableCreated;
    }

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<CreateTable>(info.copy(), messageTable, sharedState, id,
            printInfo->copy());
    }

private:
    binder::BoundCreateTableInfo info;
    std::shared_ptr<CreateTableSharedState> sharedState;
};

} // namespace processor
} // namespace lbug
