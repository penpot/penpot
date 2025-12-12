#include "processor/operator/order_by/key_block_merger.h"

#include "common/system_config.h"

using namespace lbug::common;
using namespace lbug::processor;
using namespace lbug::storage;

namespace lbug {
namespace processor {

static constexpr uint64_t DATA_BLOCK_SIZE = common::TEMP_PAGE_SIZE;

MergedKeyBlocks::MergedKeyBlocks(uint32_t numBytesPerTuple, uint64_t numTuples,
    MemoryManager* memoryManager)
    : numBytesPerTuple{numBytesPerTuple},
      numTuplesPerBlock{(uint32_t)(DATA_BLOCK_SIZE / numBytesPerTuple)}, numTuples{numTuples},
      endTupleOffset{numTuplesPerBlock * numBytesPerTuple} {
    auto numKeyBlocks = numTuples / numTuplesPerBlock + (numTuples % numTuplesPerBlock ? 1 : 0);
    for (auto i = 0u; i < numKeyBlocks; i++) {
        keyBlocks.emplace_back(std::make_shared<DataBlock>(memoryManager, DATA_BLOCK_SIZE));
    }
}

// This constructor is used to convert a keyBlock to a MergedKeyBlocks.
MergedKeyBlocks::MergedKeyBlocks(uint32_t numBytesPerTuple, std::shared_ptr<DataBlock> keyBlock)
    : numBytesPerTuple{numBytesPerTuple},
      numTuplesPerBlock{(uint32_t)(DATA_BLOCK_SIZE / numBytesPerTuple)},
      numTuples{keyBlock->numTuples}, endTupleOffset{numTuplesPerBlock * numBytesPerTuple} {
    keyBlocks.emplace_back(std::move(keyBlock));
}

uint8_t* MergedKeyBlocks::getBlockEndTuplePtr(uint32_t blockIdx, uint64_t endTupleIdx,
    uint32_t endTupleBlockIdx) const {
    KU_ASSERT(blockIdx < keyBlocks.size());
    if (endTupleIdx == 0) {
        return getKeyBlockBuffer(0);
    }
    return blockIdx == endTupleBlockIdx ? getTuple(endTupleIdx - 1) + numBytesPerTuple :
                                          getKeyBlockBuffer(blockIdx) + endTupleOffset;
}

BlockPtrInfo::BlockPtrInfo(uint64_t startTupleIdx, uint64_t endTupleIdx, MergedKeyBlocks* keyBlocks)
    : keyBlocks{keyBlocks}, curBlockIdx{startTupleIdx / keyBlocks->getNumTuplesPerBlock()},
      endBlockIdx{endTupleIdx == 0 ? 0 : (endTupleIdx - 1) / keyBlocks->getNumTuplesPerBlock()},
      endTupleIdx{endTupleIdx} {
    if (startTupleIdx == endTupleIdx) {
        curTuplePtr = nullptr;
        endTuplePtr = nullptr;
        curBlockEndTuplePtr = nullptr;
    } else {
        curTuplePtr = keyBlocks->getTuple(startTupleIdx);
        endTuplePtr = keyBlocks->getBlockEndTuplePtr(endBlockIdx, endTupleIdx, endBlockIdx);
        curBlockEndTuplePtr = keyBlocks->getBlockEndTuplePtr(curBlockIdx, endTupleIdx, endBlockIdx);
    }
}

void BlockPtrInfo::updateTuplePtrIfNecessary() {
    if (curTuplePtr == curBlockEndTuplePtr) {
        curBlockIdx++;
        if (curBlockIdx <= endBlockIdx) {
            curTuplePtr = keyBlocks->getKeyBlockBuffer(curBlockIdx);
            curBlockEndTuplePtr =
                keyBlocks->getBlockEndTuplePtr(curBlockIdx, endTupleIdx, endBlockIdx);
        }
    }
}

uint64_t KeyBlockMergeTask::findRightKeyBlockIdx(uint8_t* leftEndTuplePtr) const {
    // Find a tuple in the right memory block such that:
    // 1. The value of the current tuple is smaller than the value in leftEndTuple.
    // 2. Either the value of next tuple is larger than the value in leftEndTuple or
    // the current tuple is the last tuple in the right memory block.
    int64_t startIdx = rightKeyBlockNextIdx;
    int64_t endIdx = rightKeyBlock->getNumTuples() - 1;

    while (startIdx <= endIdx) {
        uint64_t curTupleIdx = (startIdx + endIdx) / 2;
        uint8_t* curTuplePtr = rightKeyBlock->getTuple(curTupleIdx);

        if (keyBlockMerger.compareTuplePtr(leftEndTuplePtr, curTuplePtr)) {
            if (curTupleIdx == rightKeyBlock->getNumTuples() - 1 ||
                !keyBlockMerger.compareTuplePtr(leftEndTuplePtr,
                    rightKeyBlock->getTuple(curTupleIdx + 1))) {
                // If the current tuple is the last tuple or the value of next tuple is larger than
                // the value of leftEndTuple, return the curTupleIdx.
                return curTupleIdx;
            } else {
                startIdx = curTupleIdx + 1;
            }
        } else {
            endIdx = curTupleIdx - 1;
        }
    }
    // If such tuple doesn't exist, return -1.
    return -1;
}

std::unique_ptr<KeyBlockMergeMorsel> KeyBlockMergeTask::getMorsel() {
    // We grab a batch of tuples from the left memory block, then do a binary search on the
    // right memory block to find the range of tuples to merge.
    activeMorsels++;
    if (rightKeyBlockNextIdx >= rightKeyBlock->getNumTuples()) {
        // If there is no more tuples left in the right key block,
        // just append all tuples in the left key block to the result key block.
        auto keyBlockMergeMorsel =
            std::make_unique<KeyBlockMergeMorsel>(leftKeyBlockNextIdx, leftKeyBlock->getNumTuples(),
                rightKeyBlock->getNumTuples(), rightKeyBlock->getNumTuples());
        leftKeyBlockNextIdx = leftKeyBlock->getNumTuples();
        return keyBlockMergeMorsel;
    }

    auto leftKeyBlockStartIdx = leftKeyBlockNextIdx;
    leftKeyBlockNextIdx += batch_size;

    if (leftKeyBlockNextIdx >= leftKeyBlock->getNumTuples()) {
        // This is the last batch of tuples in the left key block to merge, so just merge it with
        // remaining tuples of the right key block.
        auto keyBlockMergeMorsel = std::make_unique<KeyBlockMergeMorsel>(leftKeyBlockStartIdx,
            std::min(leftKeyBlockNextIdx, leftKeyBlock->getNumTuples()), rightKeyBlockNextIdx,
            rightKeyBlock->getNumTuples());
        rightKeyBlockNextIdx = rightKeyBlock->getNumTuples();
        return keyBlockMergeMorsel;
    } else {
        // Conduct a binary search to find the ending index in the right memory block.
        auto leftEndIdxPtr = leftKeyBlock->getTuple(leftKeyBlockNextIdx - 1);
        auto rightEndIdx = findRightKeyBlockIdx(leftEndIdxPtr);

        auto keyBlockMergeMorsel = std::make_unique<KeyBlockMergeMorsel>(leftKeyBlockStartIdx,
            std::min(leftKeyBlockNextIdx, leftKeyBlock->getNumTuples()), rightKeyBlockNextIdx,
            rightEndIdx == (uint64_t)-1 ? rightKeyBlockNextIdx : ++rightEndIdx);

        if (rightEndIdx != (uint64_t)-1) {
            rightKeyBlockNextIdx = rightEndIdx;
        }
        return keyBlockMergeMorsel;
    }
}

void KeyBlockMerger::mergeKeyBlocks(KeyBlockMergeMorsel& keyBlockMergeMorsel) const {
    KU_ASSERT(keyBlockMergeMorsel.leftKeyBlockStartIdx < keyBlockMergeMorsel.leftKeyBlockEndIdx ||
              keyBlockMergeMorsel.rightKeyBlockStartIdx < keyBlockMergeMorsel.rightKeyBlockEndIdx);

    auto leftBlockPtrInfo = BlockPtrInfo(keyBlockMergeMorsel.leftKeyBlockStartIdx,
        keyBlockMergeMorsel.leftKeyBlockEndIdx,
        keyBlockMergeMorsel.keyBlockMergeTask->leftKeyBlock.get());

    auto rightBlockPtrInfo = BlockPtrInfo(keyBlockMergeMorsel.rightKeyBlockStartIdx,
        keyBlockMergeMorsel.rightKeyBlockEndIdx,
        keyBlockMergeMorsel.keyBlockMergeTask->rightKeyBlock.get());

    auto resultBlockPtrInfo = BlockPtrInfo(keyBlockMergeMorsel.leftKeyBlockStartIdx +
                                               keyBlockMergeMorsel.rightKeyBlockStartIdx,
        keyBlockMergeMorsel.leftKeyBlockEndIdx + keyBlockMergeMorsel.rightKeyBlockEndIdx,
        keyBlockMergeMorsel.keyBlockMergeTask->resultKeyBlock.get());

    while (leftBlockPtrInfo.hasMoreTuplesToRead() && rightBlockPtrInfo.hasMoreTuplesToRead()) {
        uint64_t nextNumBytesToMerge =
            std::min(std::min(leftBlockPtrInfo.getNumBytesLeftInCurBlock(),
                         rightBlockPtrInfo.getNumBytesLeftInCurBlock()),
                resultBlockPtrInfo.getNumBytesLeftInCurBlock());
        for (auto i = 0u; i < nextNumBytesToMerge; i += numBytesPerTuple) {
            if (compareTuplePtr(leftBlockPtrInfo.curTuplePtr, rightBlockPtrInfo.curTuplePtr)) {
                memcpy(resultBlockPtrInfo.curTuplePtr, rightBlockPtrInfo.curTuplePtr,
                    numBytesPerTuple);
                rightBlockPtrInfo.curTuplePtr += numBytesPerTuple;
            } else {
                memcpy(resultBlockPtrInfo.curTuplePtr, leftBlockPtrInfo.curTuplePtr,
                    numBytesPerTuple);
                leftBlockPtrInfo.curTuplePtr += numBytesPerTuple;
            }
            resultBlockPtrInfo.curTuplePtr += numBytesPerTuple;
        }
        leftBlockPtrInfo.updateTuplePtrIfNecessary();
        rightBlockPtrInfo.updateTuplePtrIfNecessary();
        resultBlockPtrInfo.updateTuplePtrIfNecessary();
    }

    copyRemainingBlockDataToResult(rightBlockPtrInfo, resultBlockPtrInfo);
    copyRemainingBlockDataToResult(leftBlockPtrInfo, resultBlockPtrInfo);
}

// This function returns true if the value in the leftTuplePtr is larger than the value in the
// rightTuplePtr.
bool KeyBlockMerger::compareTuplePtrWithStringCol(uint8_t* leftTuplePtr,
    uint8_t* rightTuplePtr) const {
    // We can't simply use memcmp to compare tuples if there are string columns.
    // We should only compare the binary strings starting from the last compared string column
    // till the next string column.
    uint64_t lastComparedBytes = 0;
    for (auto& strKeyColInfo : strKeyColsInfo) {
        auto result = memcmp(leftTuplePtr + lastComparedBytes, rightTuplePtr + lastComparedBytes,
            strKeyColInfo.colOffsetInEncodedKeyBlock - lastComparedBytes +
                strKeyColInfo.getEncodingSize());
        // If both sides are nulls, we can just continue to check the next string column.
        auto leftStrColPtr = leftTuplePtr + strKeyColInfo.colOffsetInEncodedKeyBlock;
        auto rightStrColPtr = rightTuplePtr + strKeyColInfo.colOffsetInEncodedKeyBlock;
        if (OrderByKeyEncoder::isNullVal(leftStrColPtr, strKeyColInfo.isAscOrder) &&
            OrderByKeyEncoder::isNullVal(rightStrColPtr, strKeyColInfo.isAscOrder)) {
            lastComparedBytes =
                strKeyColInfo.colOffsetInEncodedKeyBlock + strKeyColInfo.getEncodingSize();
            continue;
        }

        // If there is a tie, we need to compare the overflow ptr of strings values.
        if (result == 0) {
            // We do an optimization here to minimize the number of times that we fetch
            // strings from factorizedTable. If both left and right strings are short string,
            // they must equal to each other (since there are no other characters to compare for
            // them). If one string is long string and the other string is short string, the
            // long string must be greater than the short string.
            bool isLeftStrLong =
                OrderByKeyEncoder::isLongStr(leftStrColPtr, strKeyColInfo.isAscOrder);
            bool isRightStrLong =
                OrderByKeyEncoder::isLongStr(rightStrColPtr, strKeyColInfo.isAscOrder);
            if (!isLeftStrLong && !isRightStrLong) {
                continue;
            } else if (isLeftStrLong && !isRightStrLong) {
                return strKeyColInfo.isAscOrder;
            } else if (!isLeftStrLong && isRightStrLong) {
                return !strKeyColInfo.isAscOrder;
            }

            auto leftTupleInfo = leftTuplePtr + numBytesToCompare;
            auto rightTupleInfo = rightTuplePtr + numBytesToCompare;
            auto leftBlockIdx = OrderByKeyEncoder::getEncodedFTBlockIdx(leftTupleInfo);
            auto leftBlockOffset = OrderByKeyEncoder::getEncodedFTBlockOffset(leftTupleInfo);
            auto rightBlockIdx = OrderByKeyEncoder::getEncodedFTBlockIdx(rightTupleInfo);
            auto rightBlockOffset = OrderByKeyEncoder::getEncodedFTBlockOffset(rightTupleInfo);

            auto& leftFactorizedTable =
                factorizedTables[OrderByKeyEncoder::getEncodedFTIdx(leftTupleInfo)];
            auto& rightFactorizedTable =
                factorizedTables[OrderByKeyEncoder::getEncodedFTIdx(rightTupleInfo)];
            auto leftStr = leftFactorizedTable->getData<ku_string_t>(leftBlockIdx, leftBlockOffset,
                strKeyColInfo.colOffsetInFT);
            auto rightStr = rightFactorizedTable->getData<ku_string_t>(rightBlockIdx,
                rightBlockOffset, strKeyColInfo.colOffsetInFT);
            result = (leftStr == rightStr);
            if (result) {
                // If the tie can't be solved, we need to check the next string column.
                lastComparedBytes =
                    strKeyColInfo.colOffsetInEncodedKeyBlock + strKeyColInfo.getEncodingSize();
                continue;
            }
            result = leftStr > rightStr;
            return strKeyColInfo.isAscOrder == result;
        }
        return result > 0;
    }
    // The string tie can't be solved, just add the tuple in the leftMemBlock to
    // resultMemBlock.
    return false;
}

void KeyBlockMerger::copyRemainingBlockDataToResult(BlockPtrInfo& blockToCopy,
    BlockPtrInfo& resultBlock) const {
    while (blockToCopy.curBlockIdx <= blockToCopy.endBlockIdx) {
        uint64_t nextNumBytesToMerge = std::min(blockToCopy.getNumBytesLeftInCurBlock(),
            resultBlock.getNumBytesLeftInCurBlock());
        for (auto i = 0u; i < nextNumBytesToMerge; i += numBytesPerTuple) {
            memcpy(resultBlock.curTuplePtr, blockToCopy.curTuplePtr, numBytesPerTuple);
            blockToCopy.curTuplePtr += numBytesPerTuple;
            resultBlock.curTuplePtr += numBytesPerTuple;
        }
        blockToCopy.updateTuplePtrIfNecessary();
        resultBlock.updateTuplePtrIfNecessary();
    }
}

std::unique_ptr<KeyBlockMergeMorsel> KeyBlockMergeTaskDispatcher::getMorsel() {
    if (isDoneMerge()) {
        return nullptr;
    }
    std::lock_guard<std::mutex> keyBlockMergeDispatcherLock{mtx};

    if (!activeKeyBlockMergeTasks.empty() && activeKeyBlockMergeTasks.back()->hasMorselLeft()) {
        // If there are morsels left in the lastMergeTask, just give it to the caller.
        auto morsel = activeKeyBlockMergeTasks.back()->getMorsel();
        morsel->keyBlockMergeTask = activeKeyBlockMergeTasks.back();
        return morsel;
    } else if (sortedKeyBlocks->size() > 1) {
        // If there are no morsels left in the lastMergeTask, we just create a new merge task.
        auto leftKeyBlock = sortedKeyBlocks->front();
        sortedKeyBlocks->pop();
        auto rightKeyBlock = sortedKeyBlocks->front();
        sortedKeyBlocks->pop();
        auto resultKeyBlock = std::make_shared<MergedKeyBlocks>(leftKeyBlock->getNumBytesPerTuple(),
            leftKeyBlock->getNumTuples() + rightKeyBlock->getNumTuples(), memoryManager);
        auto newMergeTask = std::make_shared<KeyBlockMergeTask>(leftKeyBlock, rightKeyBlock,
            resultKeyBlock, *keyBlockMerger);
        activeKeyBlockMergeTasks.emplace_back(newMergeTask);
        auto morsel = newMergeTask->getMorsel();
        morsel->keyBlockMergeTask = newMergeTask;
        return morsel;
    } else {
        // There is no morsel can be given at this time, just wait for the ongoing merge
        // task to finish.
        return nullptr;
    }
}

void KeyBlockMergeTaskDispatcher::doneMorsel(std::unique_ptr<KeyBlockMergeMorsel> morsel) {
    std::lock_guard<std::mutex> keyBlockMergeDispatcherLock{mtx};
    // If there is no active and morsels left tin the keyBlockMergeTask, just remove it from
    // the active keyBlockMergeTask and add the result key block to the sortedKeyBlocks queue.
    if ((--morsel->keyBlockMergeTask->activeMorsels) == 0 &&
        !morsel->keyBlockMergeTask->hasMorselLeft()) {
        erase(activeKeyBlockMergeTasks, morsel->keyBlockMergeTask);
        sortedKeyBlocks->emplace(morsel->keyBlockMergeTask->resultKeyBlock);
    }
}

void KeyBlockMergeTaskDispatcher::init(MemoryManager* memoryManager,
    std::queue<std::shared_ptr<MergedKeyBlocks>>* sortedKeyBlocks,
    std::vector<FactorizedTable*> factorizedTables, std::vector<StrKeyColInfo>& strKeyColsInfo,
    uint64_t numBytesPerTuple) {
    KU_ASSERT(this->keyBlockMerger == nullptr);
    this->memoryManager = memoryManager;
    this->sortedKeyBlocks = sortedKeyBlocks;
    this->keyBlockMerger = std::make_unique<KeyBlockMerger>(std::move(factorizedTables),
        strKeyColsInfo, numBytesPerTuple);
}

} // namespace processor
} // namespace lbug
