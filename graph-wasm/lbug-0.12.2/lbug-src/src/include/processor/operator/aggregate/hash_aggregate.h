#pragma once

#include <sys/types.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "aggregate_hash_table.h"
#include "common/cast.h"
#include "common/copy_constructors.h"
#include "common/data_chunk/data_chunk_state.h"
#include "common/in_mem_overflow_buffer.h"
#include "common/mpsc_queue.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "processor/operator/aggregate/aggregate_input.h"
#include "processor/operator/aggregate/base_aggregate.h"
#include "processor/operator/physical_operator.h"
#include "processor/result/factorized_table.h"
#include "processor/result/factorized_table_schema.h"

namespace lbug {
namespace processor {

struct HashAggregateInfo {
    std::vector<DataPos> flatKeysPos;
    std::vector<DataPos> unFlatKeysPos;
    std::vector<DataPos> dependentKeysPos;
    FactorizedTableSchema tableSchema;

    HashAggregateInfo(std::vector<DataPos> flatKeysPos, std::vector<DataPos> unFlatKeysPos,
        std::vector<DataPos> dependentKeysPos, FactorizedTableSchema tableSchema);
    EXPLICIT_COPY_DEFAULT_MOVE(HashAggregateInfo);

private:
    HashAggregateInfo(const HashAggregateInfo& other);
};

// NOLINTNEXTLINE(cppcoreguidelines-virtual-class-destructor): This is a final class.
class HashAggregateSharedState final : public BaseAggregateSharedState,
                                       public AggregatePartitioningData {

public:
    explicit HashAggregateSharedState(main::ClientContext* context, HashAggregateInfo hashAggInfo,
        const std::vector<function::AggregateFunction>& aggregateFunctions,
        std::span<AggregateInfo> aggregateInfos, std::vector<common::LogicalType> keyTypes,
        std::vector<common::LogicalType> payloadTypes);

    void appendTuples(const FactorizedTable& factorizedTable, ft_col_offset_t hashOffset) override {
        auto numBytesPerTuple = factorizedTable.getTableSchema()->getNumBytesPerTuple();
        for (ft_tuple_idx_t tupleIdx = 0; tupleIdx < factorizedTable.getNumTuples(); tupleIdx++) {
            auto tuple = factorizedTable.getTuple(tupleIdx);
            auto hash = *reinterpret_cast<common::hash_t*>(tuple + hashOffset);
            auto& partition =
                globalPartitions[(hash >> shiftForPartitioning) % globalPartitions.size()];
            partition.queue->appendTuple(std::span(tuple, numBytesPerTuple));
        }
    }

    void appendDistinctTuple(size_t distinctFuncIndex, std::span<uint8_t> tuple,
        common::hash_t hash) override {
        auto& partition =
            globalPartitions[(hash >> shiftForPartitioning) % globalPartitions.size()];
        partition.distinctTableQueues[distinctFuncIndex]->appendTuple(tuple);
    }

    void appendOverflow(common::InMemOverflowBuffer&& overflowBuffer) override {
        overflow.push(std::make_unique<common::InMemOverflowBuffer>(std::move(overflowBuffer)));
    }

    void finalizePartitions();

    std::pair<uint64_t, uint64_t> getNextRangeToRead() override;

    void scan(std::span<uint8_t*> entries, std::vector<common::ValueVector*>& keyVectors,
        common::offset_t startOffset, common::offset_t numRowsToScan,
        std::vector<uint32_t>& columnIndices);

    uint64_t getNumTuples() const;

    uint64_t getCurrentOffset() const { return currentOffset; }

    void setLimitNumber(uint64_t num) { limitNumber = num; }
    uint64_t getLimitNumber() const { return limitNumber; }

    const FactorizedTableSchema* getTableSchema() const {
        return globalPartitions[0].hashTable->getTableSchema();
    }

    const HashAggregateInfo& getAggregateInfo() const { return aggInfo; }

    void assertFinalized() const;

protected:
    std::tuple<const FactorizedTable*, common::offset_t> getPartitionForOffset(
        common::offset_t offset) const;

    struct Partition {
        std::unique_ptr<AggregateHashTable> hashTable;
        std::mutex mtx;
        std::unique_ptr<HashTableQueue> queue;
        // The tables storing the distinct values for distinct aggregate functions all get merged in
        // the same way as the main table
        std::vector<std::unique_ptr<HashTableQueue>> distinctTableQueues;
        std::atomic<bool> finalized = false;
    };

public:
    HashAggregateInfo aggInfo;
    uint64_t limitNumber;
    storage::MemoryManager* memoryManager;
    std::vector<Partition> globalPartitions;
};

struct HashAggregateLocalState {
    std::vector<common::ValueVector*> keyVectors;
    std::vector<common::ValueVector*> dependentKeyVectors;
    common::DataChunkState* leadingState = nullptr;
    std::unique_ptr<PartitioningAggregateHashTable> aggregateHashTable;

    void init(HashAggregateSharedState* sharedState, ResultSet& resultSet,
        main::ClientContext* context, std::vector<function::AggregateFunction>& aggregateFunctions,
        std::vector<common::LogicalType> types);
    uint64_t append(const std::vector<AggregateInput>& aggregateInputs,
        uint64_t multiplicity) const;
};

struct HashAggregatePrintInfo final : OPPrintInfo {
    binder::expression_vector keys;
    binder::expression_vector aggregates;
    uint64_t limitNum;

    HashAggregatePrintInfo(binder::expression_vector keys, binder::expression_vector aggregates)
        : keys{std::move(keys)}, aggregates{std::move(aggregates)}, limitNum{UINT64_MAX} {}

    std::string toString() const override;

    std::unique_ptr<OPPrintInfo> copy() const override {
        return std::unique_ptr<HashAggregatePrintInfo>(new HashAggregatePrintInfo(*this));
    }

private:
    HashAggregatePrintInfo(const HashAggregatePrintInfo& other)
        : OPPrintInfo{other}, keys{other.keys}, aggregates{other.aggregates},
          limitNum{other.limitNum} {}
};

class HashAggregate final : public BaseAggregate {
public:
    HashAggregate(std::shared_ptr<BaseAggregateSharedState> sharedState,
        std::vector<function::AggregateFunction> aggregateFunctions,
        std::vector<AggregateInfo> aggInfos, std::unique_ptr<PhysicalOperator> child, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : BaseAggregate{std::move(sharedState), std::move(aggregateFunctions), std::move(aggInfos),
              std::move(child), id, std::move(printInfo)} {}

    void initLocalStateInternal(ResultSet* resultSet, ExecutionContext* context) override;

    void executeInternal(ExecutionContext* context) override;

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<HashAggregate>(sharedState, copyVector(aggregateFunctions),
            copyVector(aggInfos), children[0]->copy(), id, printInfo->copy());
    }

    const HashAggregateSharedState& getSharedStateReference() const {
        return common::ku_dynamic_cast<const HashAggregateSharedState&>(*sharedState);
    }
    std::shared_ptr<HashAggregateSharedState> getSharedState() const {
        return std::reinterpret_pointer_cast<HashAggregateSharedState>(sharedState);
    }

private:
    HashAggregateLocalState localState;
};

class HashAggregateFinalize final : public Sink {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::AGGREGATE_FINALIZE;

public:
    HashAggregateFinalize(std::shared_ptr<HashAggregateSharedState> sharedState, physical_op_id id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : Sink{type_, id, std::move(printInfo)}, sharedState{std::move(sharedState)} {}

    bool isSource() const override { return true; }

    void executeInternal(ExecutionContext* /*context*/) override {
        KU_ASSERT(sharedState->isReadyForFinalization());
        sharedState->finalizePartitions();
    }
    void finalizeInternal(ExecutionContext* /*context*/) override {
        sharedState->assertFinalized();
    }

    std::unique_ptr<PhysicalOperator> copy() override {
        return make_unique<HashAggregateFinalize>(sharedState, id, printInfo->copy());
    }

private:
    std::shared_ptr<HashAggregateSharedState> sharedState;
};

} // namespace processor
} // namespace lbug
