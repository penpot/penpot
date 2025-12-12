#pragma once

#include "processor/operator/aggregate/base_aggregate_scan.h"
#include "processor/operator/aggregate/hash_aggregate.h"

namespace lbug {
namespace processor {

class HashAggregateScan final : public BaseAggregateScan {
public:
    HashAggregateScan(std::shared_ptr<HashAggregateSharedState> sharedState,
        std::vector<DataPos> groupByKeyVectorsPos, AggregateScanInfo scanInfo, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : BaseAggregateScan{std::move(scanInfo), id, std::move(printInfo)},
          groupByKeyVectorsPos{std::move(groupByKeyVectorsPos)},
          sharedState{std::move(sharedState)} {}

    std::shared_ptr<HashAggregateSharedState> getSharedState() const { return sharedState; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<HashAggregateScan>(sharedState, groupByKeyVectorsPos, scanInfo, id,
            printInfo->copy());
    }

    double getProgress(ExecutionContext* context) const override;

private:
    std::vector<DataPos> groupByKeyVectorsPos;
    std::vector<common::ValueVector*> groupByKeyVectors;
    std::shared_ptr<HashAggregateSharedState> sharedState;
    std::vector<uint32_t> groupByKeyVectorsColIdxes;
    std::vector<uint8_t*> entries;
};

} // namespace processor
} // namespace lbug
