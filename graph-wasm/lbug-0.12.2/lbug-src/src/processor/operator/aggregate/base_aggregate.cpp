#include "processor/operator/aggregate/base_aggregate.h"

#include "main/client_context.h"
#include "processor/operator/aggregate/aggregate_hash_table.h"

using namespace lbug::function;

namespace lbug {
namespace processor {

size_t getNumPartitionsForParallelism(main::ClientContext* context) {
    return context->getMaxNumThreadForExec();
}

BaseAggregateSharedState::BaseAggregateSharedState(
    const std::vector<AggregateFunction>& aggregateFunctions, size_t numPartitions)
    : currentOffset{0}, aggregateFunctions{copyVector(aggregateFunctions)}, numThreads{0},
      // numPartitions - 1 since we want the bit width of the largest value that
      // could be used to index the partitions
      shiftForPartitioning{
          static_cast<uint8_t>(sizeof(common::hash_t) * 8 - std::bit_width(numPartitions - 1))},
      readyForFinalization{false} {}

void BaseAggregate::initLocalStateInternal(ResultSet* resultSet, ExecutionContext* /*context*/) {
    for (auto& info : aggInfos) {
        auto aggregateInput = AggregateInput();
        if (info.aggVectorPos.dataChunkPos == INVALID_DATA_CHUNK_POS) {
            aggregateInput.aggregateVector = nullptr;
        } else {
            aggregateInput.aggregateVector = resultSet->getValueVector(info.aggVectorPos).get();
        }
        for (auto dataChunkPos : info.multiplicityChunksPos) {
            aggregateInput.multiplicityChunks.push_back(
                resultSet->getDataChunk(dataChunkPos).get());
        }
        aggInputs.push_back(std::move(aggregateInput));
    }
}

BaseAggregateSharedState::HashTableQueue::HashTableQueue(storage::MemoryManager* memoryManager,
    FactorizedTableSchema tableSchema) {
    headBlock = new TupleBlock(memoryManager, std::move(tableSchema));
    numTuplesPerBlock = headBlock.load()->table.getNumTuplesPerBlock();
}

BaseAggregateSharedState::HashTableQueue::~HashTableQueue() {
    delete headBlock.load();
    TupleBlock* block = nullptr;
    while (queuedTuples.pop(block)) {
        delete block;
    }
}

void BaseAggregateSharedState::HashTableQueue::appendTuple(std::span<uint8_t> tuple) {
    while (true) {
        auto* block = headBlock.load();
        KU_ASSERT(tuple.size() == block->table.getTableSchema()->getNumBytesPerTuple());
        auto posToWrite = block->numTuplesReserved++;
        if (posToWrite < numTuplesPerBlock) {
            memcpy(block->table.getTuple(posToWrite), tuple.data(), tuple.size());
            block->numTuplesWritten++;
            return;
        } else {
            // No more space in the block, allocate and replace it
            auto* newBlock = new TupleBlock(block->table.getMemoryManager(),
                block->table.getTableSchema()->copy());
            if (headBlock.compare_exchange_strong(block, newBlock)) {
                // TODO(bmwinger): if the queuedTuples has at least a certain size (benchmark to see
                // if there's a benefit to waiting for multiple blocks) then cycle through the queue
                // and flush any blocks which have been fully written
                queuedTuples.push(block);
            } else {
                // If the block was replaced by another thread, discard the block we created and try
                // again with the block allocated by the other thread
                delete newBlock;
            }
        }
    }
}

void BaseAggregateSharedState::HashTableQueue::mergeInto(AggregateHashTable& hashTable) {
    TupleBlock* partitionToMerge = nullptr;
    auto headBlock = this->headBlock.load();
    KU_ASSERT(headBlock != nullptr);
    while (queuedTuples.pop(partitionToMerge)) {
        KU_ASSERT(
            partitionToMerge->numTuplesWritten == partitionToMerge->table.getNumTuplesPerBlock());
        hashTable.merge(std::move(partitionToMerge->table));
        delete partitionToMerge;
    }
    if (headBlock->numTuplesWritten > 0) {
        headBlock->table.resize(headBlock->numTuplesWritten);
        hashTable.merge(std::move(headBlock->table));
    }
    delete headBlock;
    this->headBlock = nullptr;
}

} // namespace processor
} // namespace lbug
