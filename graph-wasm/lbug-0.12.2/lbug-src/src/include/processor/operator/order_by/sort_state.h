#pragma once

#include <queue>

#include "processor/operator/order_by/radix_sort.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

class SortSharedState {
public:
    SortSharedState() : nextTableIdx{0}, numBytesPerTuple{0} {
        sortedKeyBlocks = std::make_unique<std::queue<std::shared_ptr<MergedKeyBlocks>>>();
    }

    inline uint64_t getNumBytesPerTuple() const { return numBytesPerTuple; }

    inline std::vector<StrKeyColInfo>& getStrKeyColInfo() { return strKeyColsInfo; }

    inline std::queue<std::shared_ptr<MergedKeyBlocks>>* getSortedKeyBlocks() {
        return sortedKeyBlocks.get();
    }

    void init(const OrderByDataInfo& orderByDataInfo);

    std::pair<uint64_t, FactorizedTable*> getLocalPayloadTable(
        storage::MemoryManager& memoryManager, const FactorizedTableSchema& payloadTableSchema);

    void appendLocalSortedKeyBlock(const std::shared_ptr<MergedKeyBlocks>& mergedDataBlocks);

    void combineFTHasNoNullGuarantee();

    std::vector<FactorizedTable*> getPayloadTables() const;

    inline MergedKeyBlocks* getMergedKeyBlock() const {
        return sortedKeyBlocks->empty() ? nullptr : sortedKeyBlocks->front().get();
    }

private:
    std::mutex mtx;
    std::vector<std::unique_ptr<FactorizedTable>> payloadTables;
    uint8_t nextTableIdx;
    std::unique_ptr<std::queue<std::shared_ptr<MergedKeyBlocks>>> sortedKeyBlocks;
    uint32_t numBytesPerTuple;
    std::vector<StrKeyColInfo> strKeyColsInfo;
};

class SortLocalState {
public:
    void init(const OrderByDataInfo& orderByDataInfo, SortSharedState& sharedState,
        storage::MemoryManager* memoryManager);

    void append(const std::vector<common::ValueVector*>& keyVectors,
        const std::vector<common::ValueVector*>& payloadVectors);

    void finalize(SortSharedState& sharedState);

private:
    std::unique_ptr<OrderByKeyEncoder> orderByKeyEncoder;
    std::unique_ptr<RadixSort> radixSorter;
    uint64_t globalIdx = UINT64_MAX;
    FactorizedTable* payloadTable = nullptr;
};

class PayloadScanner {
public:
    PayloadScanner(MergedKeyBlocks* keyBlockToScan, std::vector<FactorizedTable*> payloadTables,
        uint64_t skipNumber = UINT64_MAX, uint64_t limitNumber = UINT64_MAX);

    uint64_t scan(std::vector<common::ValueVector*> vectorsToRead);

private:
    bool scanSingleTuple(std::vector<common::ValueVector*> vectorsToRead) const;

    void applyLimitOnResultVectors(std::vector<common::ValueVector*> vectorsToRead);

private:
    bool hasUnflatColInPayload;
    uint32_t payloadIdxOffset;
    std::vector<uint32_t> colsToScan;
    std::unique_ptr<uint8_t*[]> tuplesToRead;
    std::unique_ptr<BlockPtrInfo> blockPtrInfo;
    MergedKeyBlocks* keyBlockToScan;
    uint32_t nextTupleIdxToReadInMergedKeyBlock;
    uint64_t endTuplesIdxToReadInMergedKeyBlock;
    std::vector<FactorizedTable*> payloadTables;
    uint64_t limitNumber;
};

} // namespace processor
} // namespace lbug
