#pragma once

#include "top_k.h"

namespace lbug {
namespace processor {

struct TopKLocalScanState {
    std::vector<common::ValueVector*> vectorsToScan;
    std::unique_ptr<PayloadScanner> payloadScanner;

    void init(std::vector<DataPos>& outVectorPos, TopKSharedState& sharedState,
        ResultSet& resultSet);

    // NOLINTNEXTLINE(readability-make-member-function-const): Semantically non-const.
    inline uint64_t scan() { return payloadScanner->scan(vectorsToScan); }
};

class TopKScan final : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::TOP_K_SCAN;

public:
    TopKScan(std::vector<DataPos> outVectorPos, std::shared_ptr<TopKSharedState> sharedState,
        physical_op_id id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, id, std::move(printInfo)}, outVectorPos{std::move(outVectorPos)},
          localState{std::make_unique<TopKLocalScanState>()}, sharedState{std::move(sharedState)} {}

    bool isSource() const override { return true; }
    // Ordered table should be scanned in single-thread mode.
    bool isParallel() const override { return false; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<TopKScan>(outVectorPos, sharedState, id, printInfo->copy());
    }

private:
    std::vector<DataPos> outVectorPos;
    std::unique_ptr<TopKLocalScanState> localState;
    std::shared_ptr<TopKSharedState> sharedState;
};

} // namespace processor
} // namespace lbug
