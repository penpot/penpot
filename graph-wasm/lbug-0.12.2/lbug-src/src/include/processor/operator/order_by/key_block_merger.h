#pragma once

#include <mutex>
#include <queue>

#include "processor/operator/order_by/order_by_key_encoder.h"

namespace lbug {
namespace processor {

struct KeyBlockMergeMorsel;

// This struct stores the string key column information. We can utilize the
// pre-computed indexes and offsets to expedite the tuple comparison in merge sort.
struct StrKeyColInfo {
    StrKeyColInfo(uint32_t colOffsetInFT, uint32_t colOffsetInEncodedKeyBlock, bool isAscOrder)
        : colOffsetInFT{colOffsetInFT}, colOffsetInEncodedKeyBlock{colOffsetInEncodedKeyBlock},
          isAscOrder{isAscOrder} {}

    inline uint32_t getEncodingSize() const {
        return OrderByKeyEncoder::getEncodingSize(
            common::LogicalType(common::LogicalTypeID::STRING));
    }

    uint32_t colOffsetInFT;
    uint32_t colOffsetInEncodedKeyBlock;
    bool isAscOrder;
};

class MergedKeyBlocks {
public:
    MergedKeyBlocks(uint32_t numBytesPerTuple, uint64_t numTuples,
        storage::MemoryManager* memoryManager);

    // This constructor is used to convert a dataBlock to a MergedKeyBlocks.
    MergedKeyBlocks(uint32_t numBytesPerTuple, std::shared_ptr<DataBlock> keyBlock);

    inline uint8_t* getTuple(uint64_t tupleIdx) const {
        KU_ASSERT(tupleIdx < numTuples);
        return keyBlocks[tupleIdx / numTuplesPerBlock]->getData() +
               numBytesPerTuple * (tupleIdx % numTuplesPerBlock);
    }

    inline uint64_t getNumTuples() const { return numTuples; }

    inline uint32_t getNumBytesPerTuple() const { return numBytesPerTuple; }

    inline uint32_t getNumTuplesPerBlock() const { return numTuplesPerBlock; }

    inline uint8_t* getKeyBlockBuffer(uint32_t idx) const {
        KU_ASSERT(idx < keyBlocks.size());
        return keyBlocks[idx]->getData();
    }

    uint8_t* getBlockEndTuplePtr(uint32_t blockIdx, uint64_t endTupleIdx,
        uint32_t endTupleBlockIdx) const;

private:
    uint32_t numBytesPerTuple;
    uint32_t numTuplesPerBlock;
    uint64_t numTuples;
    std::vector<std::shared_ptr<DataBlock>> keyBlocks;
    uint32_t endTupleOffset;
};

struct BlockPtrInfo {
    BlockPtrInfo(uint64_t startTupleIdx, uint64_t endTupleIdx, MergedKeyBlocks* keyBlocks);

    inline bool hasMoreTuplesToRead() const { return curTuplePtr != endTuplePtr; }

    inline uint64_t getNumBytesLeftInCurBlock() const { return curBlockEndTuplePtr - curTuplePtr; }

    inline uint64_t getNumTuplesLeftInCurBlock() const {
        return getNumBytesLeftInCurBlock() / keyBlocks->getNumBytesPerTuple();
    }

    void updateTuplePtrIfNecessary();

    MergedKeyBlocks* keyBlocks;
    uint8_t* curTuplePtr;
    uint64_t curBlockIdx;
    uint64_t endBlockIdx;
    uint8_t* curBlockEndTuplePtr;
    uint8_t* endTuplePtr;
    uint64_t endTupleIdx;
};

class KeyBlockMerger {
public:
    explicit KeyBlockMerger(std::vector<FactorizedTable*> factorizedTables,
        std::vector<StrKeyColInfo>& strKeyColsInfo, uint32_t numBytesPerTuple)
        : factorizedTables{std::move(factorizedTables)}, strKeyColsInfo{strKeyColsInfo},
          numBytesPerTuple{numBytesPerTuple}, numBytesToCompare{numBytesPerTuple - 8},
          hasStringCol{!strKeyColsInfo.empty()} {}

    void mergeKeyBlocks(KeyBlockMergeMorsel& keyBlockMergeMorsel) const;

    inline bool compareTuplePtr(uint8_t* leftTuplePtr, uint8_t* rightTuplePtr) const {
        return hasStringCol ? compareTuplePtrWithStringCol(leftTuplePtr, rightTuplePtr) :
                              memcmp(leftTuplePtr, rightTuplePtr, numBytesToCompare) > 0;
    }

    bool compareTuplePtrWithStringCol(uint8_t* leftTuplePtr, uint8_t* rightTuplePtr) const;

private:
    void copyRemainingBlockDataToResult(BlockPtrInfo& blockToCopy, BlockPtrInfo& resultBlock) const;

private:
    // FactorizedTables[i] stores all order_by columns encoded and sorted by the ith thread.
    // MergeSort uses factorizedTable to access the full contents of the string key columns
    // when resolving ties.
    std::vector<FactorizedTable*> factorizedTables;
    // We also store the colIdxInFactorizedTable, colOffsetInEncodedKeyBlock, isAscOrder, isStrCol
    // for each string column. So, we don't need to compute them again during merge sort.
    std::vector<StrKeyColInfo>& strKeyColsInfo;
    uint32_t numBytesPerTuple;
    uint32_t numBytesToCompare;
    bool hasStringCol;
};

class KeyBlockMergeTask {
public:
    KeyBlockMergeTask(std::shared_ptr<MergedKeyBlocks> leftKeyBlock,
        std::shared_ptr<MergedKeyBlocks> rightKeyBlock,
        std::shared_ptr<MergedKeyBlocks> resultKeyBlock, KeyBlockMerger& keyBlockMerger)
        : leftKeyBlock{std::move(leftKeyBlock)}, rightKeyBlock{std::move(rightKeyBlock)},
          resultKeyBlock{std::move(resultKeyBlock)}, leftKeyBlockNextIdx{0},
          rightKeyBlockNextIdx{0}, activeMorsels{0}, keyBlockMerger{keyBlockMerger} {}

    std::unique_ptr<KeyBlockMergeMorsel> getMorsel();

    inline bool hasMorselLeft() const {
        // Returns true if there are still morsels left in the current task.
        return leftKeyBlockNextIdx < leftKeyBlock->getNumTuples() ||
               rightKeyBlockNextIdx < rightKeyBlock->getNumTuples();
    }

private:
    uint64_t findRightKeyBlockIdx(uint8_t* leftEndTuplePtr) const;

public:
    static const uint32_t batch_size = 10000;

    std::shared_ptr<MergedKeyBlocks> leftKeyBlock;
    std::shared_ptr<MergedKeyBlocks> rightKeyBlock;
    std::shared_ptr<MergedKeyBlocks> resultKeyBlock;
    uint64_t leftKeyBlockNextIdx;
    uint64_t rightKeyBlockNextIdx;
    // The counter is used to keep track of the number of morsels given to thread.
    // If the counter is 0 and there is no morsel left in the current task, we can
    // put the resultKeyBlock back to the keyBlock list.
    uint64_t activeMorsels;
    // KeyBlockMerger is used to compare the values of two tuples during the binary search.
    KeyBlockMerger& keyBlockMerger;
};

struct KeyBlockMergeMorsel {
    explicit KeyBlockMergeMorsel(uint64_t leftKeyBlockStartIdx, uint64_t leftKeyBlockEndIdx,
        uint64_t rightKeyBlockStartIdx, uint64_t rightKeyBlockEndIdx)
        : leftKeyBlockStartIdx{leftKeyBlockStartIdx}, leftKeyBlockEndIdx{leftKeyBlockEndIdx},
          rightKeyBlockStartIdx{rightKeyBlockStartIdx}, rightKeyBlockEndIdx{rightKeyBlockEndIdx} {}

    std::shared_ptr<KeyBlockMergeTask> keyBlockMergeTask;
    uint64_t leftKeyBlockStartIdx;
    uint64_t leftKeyBlockEndIdx;
    uint64_t rightKeyBlockStartIdx;
    uint64_t rightKeyBlockEndIdx;
};

// A dispatcher class used to assign KeyBlockMergeMorsel to threads.
// All functions are guaranteed to be thread-safe, so callers don't need to
// acquire a lock before calling these functions.
class KeyBlockMergeTaskDispatcher {
public:
    inline bool isDoneMerge() {
        std::lock_guard<std::mutex> keyBlockMergeDispatcherLock{mtx};
        // Returns true if there are no more merge task to do or the sortedKeyBlocks is empty
        // (meaning that the resultSet is empty).
        return sortedKeyBlocks->size() <= 1 && activeKeyBlockMergeTasks.empty();
    }

    std::unique_ptr<KeyBlockMergeMorsel> getMorsel();

    void doneMorsel(std::unique_ptr<KeyBlockMergeMorsel> morsel);

    // This function is used to initialize the columns of keyBlockMergeTaskDispatcher based on
    // sharedFactorizedTablesAndSortedKeyBlocks.
    void init(storage::MemoryManager* memoryManager,
        std::queue<std::shared_ptr<MergedKeyBlocks>>* sortedKeyBlocks,
        std::vector<FactorizedTable*> factorizedTables, std::vector<StrKeyColInfo>& strKeyColsInfo,
        uint64_t numBytesPerTuple);

private:
    std::mutex mtx;

    storage::MemoryManager* memoryManager = nullptr;
    std::queue<std::shared_ptr<MergedKeyBlocks>>* sortedKeyBlocks = nullptr;
    std::vector<std::shared_ptr<KeyBlockMergeTask>> activeKeyBlockMergeTasks;
    std::unique_ptr<KeyBlockMerger> keyBlockMerger;
};

} // namespace processor
} // namespace lbug
