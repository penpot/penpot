#pragma once

#include "processor/operator/physical_operator.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

struct CrossProductLocalState {
    std::shared_ptr<FactorizedTable> table;
    uint64_t maxMorselSize;
    uint64_t startIdx = 0u;

    CrossProductLocalState(std::shared_ptr<FactorizedTable> table, uint64_t maxMorselSize)
        : table{std::move(table)}, maxMorselSize{maxMorselSize}, startIdx{0} {}
    EXPLICIT_COPY_DEFAULT_MOVE(CrossProductLocalState);

    void init() { startIdx = table->getNumTuples(); }

private:
    CrossProductLocalState(const CrossProductLocalState& other)
        : table{other.table}, maxMorselSize{other.maxMorselSize}, startIdx{other.startIdx} {}
};

struct CrossProductInfo {
    std::vector<DataPos> outVecPos;
    std::vector<ft_col_idx_t> colIndicesToScan;

    CrossProductInfo(std::vector<DataPos> outVecPos, std::vector<ft_col_idx_t> colIndicesToScan)
        : outVecPos{std::move(outVecPos)}, colIndicesToScan{std::move(colIndicesToScan)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(CrossProductInfo);

private:
    CrossProductInfo(const CrossProductInfo& other)
        : outVecPos{other.outVecPos}, colIndicesToScan{other.colIndicesToScan} {}
};

class CrossProduct final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::CROSS_PRODUCT;

public:
    CrossProduct(CrossProductInfo info, CrossProductLocalState localState,
        std::unique_ptr<PhysicalOperator> child, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          info{std::move(info)}, localState{std::move(localState)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<CrossProduct>(info.copy(), localState.copy(), children[0]->copy(),
            id, printInfo->copy());
    }

private:
    CrossProductInfo info;
    CrossProductLocalState localState;
    std::vector<common::ValueVector*> vectorsToScan;
};

} // namespace processor
} // namespace lbug
