#pragma once

#include <memory>

#include "common/cast.h"
#include "common/copy_constructors.h"
#include "common/in_mem_overflow_buffer.h"
#include "processor/operator/aggregate/aggregate_hash_table.h"
#include "processor/operator/aggregate/base_aggregate.h"

namespace lbug {
namespace processor {

// NOLINTNEXTLINE(cppcoreguidelines-virtual-class-destructor): This is a final class.
class SimpleAggregateSharedState final : public BaseAggregateSharedState {
    friend class SimpleAggregate;

public:
    explicit SimpleAggregateSharedState(main::ClientContext* clientContext,
        const std::vector<function::AggregateFunction>& aggregateFunctions,
        const std::vector<AggregateInfo>& aggInfos);

    // The partitioningData objects need a stable pointer to this shared state
    DELETE_COPY_AND_MOVE(SimpleAggregateSharedState);

    void combineAggregateStates(
        const std::vector<std::unique_ptr<function::AggregateState>>& localAggregateStates,
        common::InMemOverflowBuffer&& localOverflowBuffer);

    void finalizeAggregateStates();

    std::pair<uint64_t, uint64_t> getNextRangeToRead() override;

    function::AggregateState* getAggregateState(uint64_t idx) {
        return globalAggregateStates[idx].get();
    }

    // Merges data from the queues into the distinct hash tables
    // Can be run concurrently (but only after all data has been written into the queues)
    void finalizePartitions(storage::MemoryManager* memoryManager,
        const std::vector<AggregateInfo>& aggInfos);

    bool isReadyForFinalization() const { return readyForFinalization; }

protected:
    struct Partition {
        struct DistinctData {
            std::unique_ptr<AggregateHashTable> hashTable;
            std::unique_ptr<HashTableQueue> queue;
            std::unique_ptr<function::AggregateState> state;
        };
        std::mutex mtx;
        std::vector<DistinctData> distinctTables;
        std::atomic<bool> finalized = false;
    };

    class SimpleAggregatePartitioningData : public AggregatePartitioningData {
    public:
        SimpleAggregatePartitioningData(SimpleAggregateSharedState* sharedState, size_t functionIdx)
            : sharedState{sharedState}, functionIdx{functionIdx} {}

        void appendTuples(const FactorizedTable& factorizedTable,
            ft_col_offset_t hashOffset) override;
        void appendDistinctTuple(size_t, std::span<uint8_t>, common::hash_t) override;
        void appendOverflow(common::InMemOverflowBuffer&& overflowBuffer) override;

    private:
        SimpleAggregateSharedState* sharedState;
        size_t functionIdx;
    };

private:
    bool hasDistinct;
    std::vector<Partition> globalPartitions;
    std::vector<SimpleAggregatePartitioningData> partitioningData;
    common::InMemOverflowBuffer aggregateOverflowBuffer;
    std::vector<std::unique_ptr<function::AggregateState>> globalAggregateStates;
};

struct SimpleAggregatePrintInfo final : OPPrintInfo {
    binder::expression_vector aggregates;

    explicit SimpleAggregatePrintInfo(binder::expression_vector aggregates)
        : aggregates{std::move(aggregates)} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<SimpleAggregatePrintInfo>(new SimpleAggregatePrintInfo(*this));
    }

private:
    SimpleAggregatePrintInfo(const SimpleAggregatePrintInfo& other)
        : OPPrintInfo{other}, aggregates{other.aggregates} {}
};

class SimpleAggregate final : public BaseAggregate {
public:
    SimpleAggregate(std::shared_ptr<BaseAggregateSharedState> sharedState,
        std::vector<function::AggregateFunction> aggregateFunctions,
        std::vector<AggregateInfo> aggInfos, std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : BaseAggregate{std::move(sharedState), std::move(aggregateFunctions), std::move(aggInfos),
              std::move(child), id, std::move(printInfo)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<SimpleAggregate>(sharedState, copyVector(aggregateFunctions),
            copyVector(aggInfos), children[0]->copy(), id, printInfo->copy());
    }

private:
    void computeAggregate(function::AggregateFunction* function, AggregateInput* input,
        function::AggregateState* state, common::InMemOverflowBuffer& overflowBuffer);

    SimpleAggregateSharedState& getSharedState() {
        return common::ku_dynamic_cast<SimpleAggregateSharedState&>(*sharedState.get());
    }

private:
    std::vector<std::unique_ptr<function::AggregateState>> localAggregateStates;
    std::vector<std::unique_ptr<PartitioningAggregateHashTable>> distinctHashTables;
};

class SimpleAggregateFinalize final : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::AGGREGATE_FINALIZE;

public:
    SimpleAggregateFinalize(std::shared_ptr<SimpleAggregateSharedState> sharedState,
        std::vector<AggregateInfo> aggInfos, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, id, std::move(printInfo)}, sharedState{std::move(sharedState)},
          aggInfos{std::move(aggInfos)} {}

    bool isSource() const override { return true; }

    void executeInternal(ExecutionContext* context) override;

    void finalizeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return std::make_unique<SimpleAggregateFinalize>(sharedState, copyVector(aggInfos), id,
            printInfo->copy());
    }

private:
    std::shared_ptr<SimpleAggregateSharedState> sharedState;
    std::vector<AggregateInfo> aggInfos;
    std::vector<std::unique_ptr<function::AggregateState>> globalAggregateStates;
};

} // namespace processor
} // namespace lbug
