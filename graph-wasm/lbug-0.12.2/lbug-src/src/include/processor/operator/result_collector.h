#pragma once

#include <mutex>

#include "binder/expression/expression.h"
#include "common/enums/accumulate_type.h"
#include "processor/operator/sink.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

class ResultCollectorSharedState {
public:
    explicit ResultCollectorSharedState(std::shared_ptr<FactorizedTable> table)
        : table{std::move(table)} {}

    void mergeLocalTable(FactorizedTable& localTable) {
        std::unique_lock lck{mtx};
        table->merge(localTable);
    }

    std::shared_ptr<FactorizedTable> getTable() { return table; }

private:
    std::mutex mtx;
    std::shared_ptr<FactorizedTable> table;
};

struct ResultCollectorInfo {
    common::AccumulateType accumulateType;
    FactorizedTableSchema tableSchema;
    std::vector<DataPos> payloadPositions;

    ResultCollectorInfo(common::AccumulateType accumulateType, FactorizedTableSchema tableSchema,
        std::vector<DataPos> payloadPositions)
        : accumulateType{accumulateType}, tableSchema{std::move(tableSchema)},
          payloadPositions{std::move(payloadPositions)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(ResultCollectorInfo);

private:
    ResultCollectorInfo(const ResultCollectorInfo& other)
        : accumulateType{other.accumulateType}, tableSchema{other.tableSchema.copy()},
          payloadPositions{other.payloadPositions} {}
};

struct ResultCollectorPrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;
    common::AccumulateType accumulateType;

    ResultCollectorPrintInfo(binder::expression_vector expressions,
        common::AccumulateType accumulateType)
        : expressions{std::move(expressions)}, accumulateType{accumulateType} {}
    ResultCollectorPrintInfo(const ResultCollectorPrintInfo& other)
        : OPPrintInfo{other}, expressions{other.expressions}, accumulateType{other.accumulateType} {
    }

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::make_unique<ResultCollectorPrintInfo>(*this);
    }
};

class ResultCollector final : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::RESULT_COLLECTOR;

public:
    ResultCollector(ResultCollectorInfo info,
        std::shared_ptr<ResultCollectorSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, std::move(child), id, std::move(printInfo)}, info{std::move(info)},
          sharedState{std::move(sharedState)} {}

    void executeInternal(ExecutionContext* context) override;

    void finalizeInternal(ExecutionContext* context) override;

    std::shared_ptr<FactorizedTable> getResultFTable() const override {
        return sharedState->getTable();
    }

    std::unique_ptr<main::QueryResult> getQueryResult() const override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<ResultCollector>(info.copy(), sharedState, children[0]->copy(), id,
            printInfo->copy());
    }

private:
    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    void initNecessaryLocalState(ResultSet* resultSet, ExecutionContext* context);

private:
    ResultCollectorInfo info;
    std::shared_ptr<ResultCollectorSharedState> sharedState;
    std::vector<common::ValueVector*> payloadVectors;
    std::vector<common::ValueVector*> payloadAndMarkVectors;

    std::unique_ptr<common::ValueVector> markVector;
    std::unique_ptr<FactorizedTable> localTable;
};

} // namespace processor
} // namespace lbug
