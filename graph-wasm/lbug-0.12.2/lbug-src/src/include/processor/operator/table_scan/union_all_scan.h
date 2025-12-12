#pragma once

#include <mutex>

#include "binder/expression/expression.h"
#include "processor/operator/physical_operator.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

struct UnionAllScanPrintInfo final : OPPrintInfo {
    binder::expression_vector expressions;

    explicit UnionAllScanPrintInfo(binder::expression_vector expressions)
        : expressions(std::move(expressions)) {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<UnionAllScanPrintInfo>(new UnionAllScanPrintInfo(*this));
    }

private:
    UnionAllScanPrintInfo(const UnionAllScanPrintInfo& other)
        : OPPrintInfo(other), expressions(other.expressions) {}
};

struct UnionAllScanInfo {
    std::vector<DataPos> outputPositions;
    std::vector<ft_col_idx_t> columnIndices;

    UnionAllScanInfo(std::vector<DataPos> outputPositions, std::vector<ft_col_idx_t> columnIndices)
        : outputPositions{std::move(outputPositions)}, columnIndices{std::move(columnIndices)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(UnionAllScanInfo);

private:
    UnionAllScanInfo(const UnionAllScanInfo& other)
        : outputPositions{other.outputPositions}, columnIndices{other.columnIndices} {}
};

struct UnionAllScanMorsel {
    FactorizedTable* table;
    uint64_t startTupleIdx;
    uint64_t numTuples;

    UnionAllScanMorsel(FactorizedTable* table, uint64_t startTupleIdx, uint64_t numTuples)
        : table{table}, startTupleIdx{startTupleIdx}, numTuples{numTuples} {}
};

class UnionAllScanSharedState {
public:
    UnionAllScanSharedState(std::vector<std::shared_ptr<FactorizedTable>> tables,
        uint64_t maxMorselSize)
        : tables{std::move(tables)}, maxMorselSize{maxMorselSize}, tableIdx{0},
          nextTupleIdxToScan{0} {}

    std::unique_ptr<UnionAllScanMorsel> getMorsel();

private:
    std::unique_ptr<UnionAllScanMorsel> getMorselNoLock(FactorizedTable* table);

private:
    std::mutex mtx;
    std::vector<std::shared_ptr<FactorizedTable>> tables;
    uint64_t maxMorselSize;
    uint64_t tableIdx;
    uint64_t nextTupleIdxToScan;
};

class UnionAllScan : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::UNION_ALL_SCAN;

public:
    UnionAllScan(UnionAllScanInfo info, std::shared_ptr<UnionAllScanSharedState> sharedState,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, id, std::move(printInfo)}, info{std::move(info)},
          sharedState{std::move(sharedState)} {}

    bool isSource() const final { return true; }

    void initLocalStateInternal(ResultSet* resultSet_, ExecutionContext* context) final;

    bool getNextTuplesInternal(ExecutionContext* context) final;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<UnionAllScan>(info.copy(), sharedState, id, printInfo->copy());
    }

private:
    UnionAllScanInfo info;
    std::shared_ptr<UnionAllScanSharedState> sharedState;
    std::vector<common::ValueVector*> vectors;
};

} // namespace processor
} // namespace lbug
