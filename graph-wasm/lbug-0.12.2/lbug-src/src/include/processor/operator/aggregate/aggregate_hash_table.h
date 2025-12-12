#pragma once

#include <cstdint>

#include "aggregate_input.h"
#include "common/copy_constructors.h"
#include "common/data_chunk/data_chunk_state.h"
#include "common/null_mask.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"
#include "function/aggregate_function.h"
#include "processor/result/base_hash_table.h"
#include "processor/result/factorized_table.h"
#include "processor/result/factorized_table_schema.h"

namespace lbug {
namespace common {
class InMemOverflowBuffer;
}
namespace storage {
class MemoryManager;
}
namespace processor {

class HashSlot {
    // upper 7 bits are for the fingerprint, the remaining 57 bits are for the pointer.
    // The largest pointer size seems to be 57 bytes for intel's 5-level paging
    static constexpr size_t FINGERPRINT_BITS = 7;
    static constexpr size_t POINTER_BITS = 57;

public:
    HashSlot(common::hash_t hash, const uint8_t* entry)
        : entry(reinterpret_cast<uint64_t>(entry) |
                (hash & common::NULL_HIGH_MASKS[FINGERPRINT_BITS])) {}

    bool checkFingerprint(common::hash_t hash) const {
        return (entry >> POINTER_BITS) == (hash >> POINTER_BITS);
    }

    // pointer to the factorizedTable entry which stores [groupKey1, ...
    // groupKeyN, aggregateState1, ..., aggregateStateN, hashValue].
    uint8_t* getEntry() const {
        return reinterpret_cast<uint8_t*>(entry & common::NULL_LOWER_MASKS[POINTER_BITS]);
    }

private:
    uint64_t entry;
};

enum class HashTableType : uint8_t { AGGREGATE_HASH_TABLE = 0, MARK_HASH_TABLE = 1 };

/**
 * AggregateHashTable Design
 *
 * 1. Payload
 * Entry layout: [groupKey1, ... groupKeyN, aggregateState1, ..., aggregateStateN, hashValue]
 * Payload is stored in the factorizedTable.
 *
 * 2. Hash slot
 * Layout : see HashSlot struct
 * If the entry is a nullptr, then the current hashSlot is unused.
 *
 * 3. Collision handling
 * Linear probing. When collision happens, we find the next hash slot whose entry is a
 * nullptr.
 *
 */
class AggregateHashTable;
using update_agg_function_t = std::function<void(AggregateHashTable*,
    const std::vector<common::ValueVector*>&, const std::vector<common::ValueVector*>&,
    function::AggregateFunction&, common::ValueVector*, uint64_t, uint32_t, uint32_t)>;

class AggregateHashTable : public BaseHashTable {
public:
    AggregateHashTable(storage::MemoryManager& memoryManager,
        const std::vector<common::LogicalType>& keyTypes,
        const std::vector<common::LogicalType>& payloadTypes, uint64_t numEntriesToAllocate,
        FactorizedTableSchema tableSchema)
        : AggregateHashTable(memoryManager, common::LogicalType::copy(keyTypes),
              common::LogicalType::copy(payloadTypes),
              std::vector<function::AggregateFunction>{} /* empty aggregates */,
              std::vector<common::LogicalType>{} /* empty distinct agg key*/, numEntriesToAllocate,
              std::move(tableSchema)) {}

    AggregateHashTable(storage::MemoryManager& memoryManager,
        std::vector<common::LogicalType> keyTypes, std::vector<common::LogicalType> payloadTypes,
        const std::vector<function::AggregateFunction>& aggregateFunctions,
        const std::vector<common::LogicalType>& distinctAggKeyTypes, uint64_t numEntriesToAllocate,
        FactorizedTableSchema tableSchema);

    //! merge aggregate hash table by combining aggregate states under the same key
    void merge(FactorizedTable&& other);
    void merge(AggregateHashTable&& other) { merge(std::move(*other.factorizedTable)); }
    // Must be called after merging hash tables with distinct functions, but only when the
    // merged distinct tuples match the merged non-distinct tuples
    void mergeDistinctAggregateInfo();

    void finalizeAggregateStates();

    void resize(uint64_t newSize);
    void clear();
    void resizeHashTableIfNecessary(uint32_t maxNumDistinctHashKeys);

    AggregateHashTable createEmptyCopy() const { return AggregateHashTable(*this); }

    DEFAULT_BOTH_MOVE(AggregateHashTable);
    AggregateHashTable* getDistinctHashTable(uint64_t aggregateFunctionIdx) const {
        return distinctHashTables[aggregateFunctionIdx].get();
    }

    void appendDistinct(const std::vector<common::ValueVector*>& keyVectors,
        common::ValueVector* aggregateVector, const common::DataChunkState* leadingState);

protected:
    virtual uint64_t append(const std::vector<common::ValueVector*>& keyVectors,
        const common::DataChunkState* leadingState,
        const std::vector<AggregateInput>& aggregateInputs, uint64_t resultSetMultiplicity) {
        return append(keyVectors, std::vector<common::ValueVector*>{} /*dependentKeyVectors*/,
            leadingState, aggregateInputs, resultSetMultiplicity);
    }

    virtual uint64_t append(const std::vector<common::ValueVector*>& keyVectors,
        const std::vector<common::ValueVector*>& dependentKeyVectors,
        const common::DataChunkState* leadingState,
        const std::vector<AggregateInput>& aggregateInputs, uint64_t resultSetMultiplicity);

    virtual uint64_t matchFTEntries(std::span<const common::ValueVector*> keyVectors,
        uint64_t numMayMatches, uint64_t numNoMatches);

    uint64_t matchFTEntries(const FactorizedTable& srcTable, uint64_t startOffset,
        uint64_t numMayMatches, uint64_t numNoMatches);

    void initializeFTEntries(const std::vector<common::ValueVector*>& keyVectors,
        const std::vector<common::ValueVector*>& dependentKeyVectors,
        uint64_t numFTEntriesToInitialize);
    void initializeFTEntries(const FactorizedTable& sourceTable, uint64_t sourceStartOffset,
        uint64_t numFTEntriesToInitialize);

    uint64_t matchUnFlatVecWithFTColumn(const common::ValueVector* vector, uint64_t numMayMatches,
        uint64_t& numNoMatches, uint32_t colIdx);

    uint64_t matchFlatVecWithFTColumn(const common::ValueVector* vector, uint64_t numMayMatches,
        uint64_t& numNoMatches, uint32_t colIdx);

    void findHashSlots(const std::vector<common::ValueVector*>& keyVectors,
        const std::vector<common::ValueVector*>& dependentKeyVectors,
        const common::DataChunkState* leadingState);

    void findHashSlots(const FactorizedTable& data, uint64_t startOffset, uint64_t numTuples);

protected:
    void initializeFT(const std::vector<function::AggregateFunction>& aggregateFunctions,
        FactorizedTableSchema&& tableSchema);

    void initializeHashTable(uint64_t numEntriesToAllocate);

    void initializeTmpVectors();

    // ! This function will only be used by distinct aggregate, which assumes that all
    // groupByKeys are flat.
    uint8_t* findEntryInDistinctHT(const std::vector<common::ValueVector*>& groupByKeyVectors,
        common::hash_t hash);

    void initializeFTEntryWithFlatVec(common::ValueVector* flatVector,
        uint64_t numEntriesToInitialize, uint32_t colIdx);

    void initializeFTEntryWithUnFlatVec(common::ValueVector* unFlatVector,
        uint64_t numEntriesToInitialize, uint32_t colIdx);

    uint8_t* createEntryInDistinctHT(const std::vector<common::ValueVector*>& groupByHashKeyVectors,
        common::hash_t hash);

    void increaseSlotIdx(uint64_t& slotIdx) const;

    void initTmpHashSlotsAndIdxes();
    void initTmpHashSlotsAndIdxes(const FactorizedTable& sourceTable, uint64_t startOffset,
        uint64_t numTuples);

    void increaseHashSlotIdxes(uint64_t numNoMatches);

    void updateAggState(const std::vector<common::ValueVector*>& keyVectors,
        function::AggregateFunction& aggregateFunction, common::ValueVector* aggVector,
        uint64_t multiplicity, uint32_t aggStateOffset,
        const common::DataChunkState* firstUnFlatState);

    void updateAggStates(const std::vector<common::ValueVector*>& keyVectors,
        const std::vector<AggregateInput>& aggregateInputs, uint64_t resultSetMultiplicity,
        const common::DataChunkState* firstUnFlatState);

    void fillEntryWithInitialNullAggregateState(FactorizedTable& table, uint8_t* entry);

    //! find an uninitialized hash slot for given hash and fill hash slot with block id and
    //! offset
    void fillHashSlot(common::hash_t hash, uint8_t* groupByKeysAndAggregateStateBuffer);

    inline HashSlot* getHashSlot(uint64_t slotIdx) {
        KU_ASSERT(slotIdx < maxNumHashSlots);
        // If the slotIdx is smaller than the numHashSlotsPerBlock, then the hashSlot must be
        // in the first hashSlotsBlock. We don't need to compute the blockIdx and blockOffset.
        return slotIdx < ((uint64_t)1 << numSlotsPerBlockLog2) ?
                   (HashSlot*)(hashSlotsBlocks[0]->getData() + slotIdx * sizeof(HashSlot)) :
                   (HashSlot*)(hashSlotsBlocks[slotIdx >> numSlotsPerBlockLog2]->getData() +
                               (slotIdx & slotIdxInBlockMask) * sizeof(HashSlot));
    }

    void addDataBlocksIfNecessary(uint64_t maxNumHashSlots);

    void updateNullAggVectorState(const common::DataChunkState& keyState,
        function::AggregateFunction& aggregateFunction, uint64_t multiplicity,
        uint32_t aggStateOffset);

    void updateBothFlatAggVectorState(function::AggregateFunction& aggregateFunction,
        common::ValueVector* aggVector, uint64_t multiplicity, uint32_t aggStateOffset);

    void updateFlatUnFlatKeyFlatAggVectorState(const common::DataChunkState& unFlatKeyState,
        function::AggregateFunction& aggregateFunction, common::ValueVector* aggVector,
        uint64_t multiplicity, uint32_t aggStateOffset);

    void updateFlatKeyUnFlatAggVectorState(const std::vector<common::ValueVector*>& flatKeyVectors,
        function::AggregateFunction& aggregateFunction, common::ValueVector* aggVector,
        uint64_t multiplicity, uint32_t aggStateOffset);

    void updateBothUnFlatSameDCAggVectorState(function::AggregateFunction& aggregateFunction,
        common::ValueVector* aggVector, uint64_t multiplicity, uint32_t aggStateOffset);

    void updateBothUnFlatDifferentDCAggVectorState(const common::DataChunkState& unFlatKeyState,
        function::AggregateFunction& aggregateFunction, common::ValueVector* aggVector,
        uint64_t multiplicity, uint32_t aggStateOffset);

    static std::vector<common::LogicalType> getDistinctAggKeyTypes(
        const AggregateHashTable& hashTable) {
        std::vector<common::LogicalType> distinctAggKeyTypes(hashTable.distinctHashTables.size());
        std::transform(hashTable.distinctHashTables.begin(), hashTable.distinctHashTables.end(),
            distinctAggKeyTypes.begin(), [&](const auto& distinctHashTable) {
                if (distinctHashTable) {
                    return distinctHashTable->keyTypes.back().copy();
                } else {
                    return common::LogicalType();
                }
            });
        return distinctAggKeyTypes;
    }

    template<class Func>
    uint8_t* findEntry(common::hash_t hash, Func compareKeys) {
        auto slotIdx = getSlotIdxForHash(hash);
        while (true) {
            auto slot = (HashSlot*)getHashSlot(slotIdx);
            if (slot->getEntry() == nullptr) {
                return nullptr;
            } else if (slot->checkFingerprint(hash) && compareKeys(slot->getEntry())) {
                return slot->getEntry();
            }
            increaseSlotIdx(slotIdx);
        }
    }

private:
    // Does not copy the contents of the hash table and is provided as a convenient way of
    // constructing more hash tables without having to hold on to or expose the construction
    // arguments via createEmptyCopy
    AggregateHashTable(const AggregateHashTable& other)
        : AggregateHashTable(*other.memoryManager, common::LogicalType::copy(other.keyTypes),
              common::LogicalType::copy(other.payloadTypes), other.aggregateFunctions,
              getDistinctAggKeyTypes(other), 0, other.getTableSchema()->copy()) {}

protected:
    uint32_t hashColIdxInFT{};
    std::unique_ptr<uint64_t[]> mayMatchIdxes;
    std::unique_ptr<uint64_t[]> noMatchIdxes;
    std::unique_ptr<uint64_t[]> entryIdxesToInitialize;
    std::unique_ptr<HashSlot*[]> hashSlotsToUpdateAggState;

    std::vector<common::LogicalType> payloadTypes;
    std::vector<function::AggregateFunction> aggregateFunctions;

    //! special handling of distinct aggregate
    std::vector<std::unique_ptr<AggregateHashTable>> distinctHashTables;
    std::vector<uint64_t> distinctHashEntriesProcessed;
    uint32_t hashColOffsetInFT{};
    uint32_t aggStateColOffsetInFT{};
    uint32_t aggStateColIdxInFT{};
    uint32_t numBytesForKeys = 0;
    uint32_t numBytesForDependentKeys = 0;
    std::vector<update_agg_function_t> updateAggFuncs;
    // Temporary arrays to hold intermediate results.
    std::unique_ptr<uint64_t[]> tmpValueIdxes;
    std::unique_ptr<uint64_t[]> tmpSlotIdxes;
};

struct AggregateHashTableUtils {
    static std::unique_ptr<AggregateHashTable> createDistinctHashTable(
        storage::MemoryManager& memoryManager,
        const std::vector<common::LogicalType>& groupByKeyTypes,
        const common::LogicalType& distinctKeyType);

    static FactorizedTableSchema getTableSchemaForKeys(
        const std::vector<common::LogicalType>& groupByKeyTypes,
        const common::LogicalType& distinctKeyType);
};

// Separate class since the SimpleAggregate has multiple different top-level destinations for
// partitioning
class AggregatePartitioningData {
public:
    virtual ~AggregatePartitioningData() = default;
    virtual void appendTuples(const FactorizedTable& table, ft_col_offset_t hashOffset) = 0;
    virtual void appendDistinctTuple(size_t /*distinctFuncIndex*/, std::span<uint8_t> /*tuple*/,
        common::hash_t /*hash*/) = 0;
    virtual void appendOverflow(common::InMemOverflowBuffer&& overflowBuffer) = 0;
};

// Fixed-sized Aggregate hash table that flushes tuples into partitions in the
// HashAggregateSharedState when full
class PartitioningAggregateHashTable final : public AggregateHashTable {
public:
    PartitioningAggregateHashTable(AggregatePartitioningData* partitioningData,
        storage::MemoryManager& memoryManager, std::vector<common::LogicalType> keyTypes,
        std::vector<common::LogicalType> payloadTypes,
        const std::vector<function::AggregateFunction>& aggregateFunctions,
        const std::vector<common::LogicalType>& distinctAggKeyTypes,
        FactorizedTableSchema tableSchema)
        : AggregateHashTable(memoryManager, std::move(keyTypes), std::move(payloadTypes),
              aggregateFunctions, distinctAggKeyTypes,
              common::DEFAULT_VECTOR_CAPACITY /*minimum size*/, tableSchema.copy()),
          tableSchema{std::move(tableSchema)}, partitioningData{partitioningData} {}

    uint64_t append(const std::vector<common::ValueVector*>& keyVectors,
        const std::vector<common::ValueVector*>& dependentKeyVectors,
        const common::DataChunkState* leadingState,
        const std::vector<AggregateInput>& aggregateInputs,
        uint64_t resultSetMultiplicity) override;

    void mergeIfFull(uint64_t tuplesToAdd, bool mergeAll = false);

private:
    FactorizedTableSchema tableSchema;
    AggregatePartitioningData* partitioningData;
};

} // namespace processor
} // namespace lbug
