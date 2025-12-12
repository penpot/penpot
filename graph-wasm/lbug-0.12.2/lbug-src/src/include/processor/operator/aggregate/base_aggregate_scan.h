#pragma once

#include "function/aggregate_function.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

using move_agg_result_to_vector_func = std::function<void(common::ValueVector& vector, uint64_t pos,
    function::AggregateState* aggregateState)>;

struct AggregateScanInfo {
    std::vector<DataPos> aggregatesPos;
    std::vector<move_agg_result_to_vector_func> moveAggResultToVectorFuncs;
};

class BaseAggregateScan : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::AGGREGATE_SCAN;

public:
    BaseAggregateScan(AggregateScanInfo scanInfo, std::unique_ptr<PhysicalOperator> child,
        uint32_t id, std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(child), id, std::move(printInfo)},
          scanInfo{std::move(scanInfo)} {}

    BaseAggregateScan(AggregateScanInfo scanInfo, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, id, std::move(printInfo)}, scanInfo{std::move(scanInfo)} {}

    bool isSource() const override { return true; }

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    bool getNextTuplesInternal(ExecutionContext* context) override = 0;

    std::unique_ptr<PhysicalOperator> copy() override = 0;

protected:
    AggregateScanInfo scanInfo;
    std::vector<std::shared_ptr<common::ValueVector>> aggregateVectors;
};

} // namespace processor
} // namespace lbug
