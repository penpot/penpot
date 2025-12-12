#pragma once

#include <queue>

#include "processor/operator/order_by/key_block_merger.h"
#include "processor/operator/order_by/order_by_key_encoder.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

struct TieRange {
public:
    uint32_t startingTupleIdx;
    uint32_t endingTupleIdx;
    inline uint32_t getNumTuples() const { return endingTupleIdx - startingTupleIdx + 1; }
    explicit TieRange(uint32_t startingTupleIdx, uint32_t endingTupleIdx)
        : startingTupleIdx{startingTupleIdx}, endingTupleIdx{endingTupleIdx} {}
};

// RadixSort sorts a block of binary strings using the radixSort and quickSort (only for comparing
// string overflow pointers). The algorithm loops through each column of the orderByVectors. If it
// sees a column with string, which is variable length, it will call radixSort to sort the columns
// seen so far. If there are tie tuples, it will compare the overflow ptr of strings. For subsequent
// columns, the algorithm only calls radixSort on tie tuples.
class RadixSort {
public:
    RadixSort(storage::MemoryManager* memoryManager, FactorizedTable& factorizedTable,
        OrderByKeyEncoder& orderByKeyEncoder, std::vector<StrKeyColInfo> strKeyColsInfo);

    void sortSingleKeyBlock(const DataBlock& keyBlock);

private:
    void radixSort(uint8_t* keyBlockPtr, uint32_t numTuplesToSort, uint32_t numBytesSorted,
        uint32_t numBytesToSort);

    std::vector<TieRange> findTies(uint8_t* keyBlockPtr, uint32_t numTuplesToFindTies,
        uint32_t numBytesToSort, uint32_t baseTupleIdx) const;

    void fillTmpTuplePtrSortingBlock(TieRange& keyBlockTie, uint8_t* keyBlockPtr);

    void reOrderKeyBlock(TieRange& keyBlockTie, uint8_t* keyBlockPtr);

    // Some ties can't be solved in quicksort, just add them to ties.
    template<typename TYPE>
    void findStringTies(TieRange& keyBlockTie, uint8_t* keyBlockPtr, std::queue<TieRange>& ties,
        StrKeyColInfo& keyColInfo);

    void solveStringTies(TieRange& keyBlockTie, uint8_t* keyBlockPtr, std::queue<TieRange>& ties,
        StrKeyColInfo& keyColInfo);

private:
    std::unique_ptr<DataBlock> tmpSortingResultBlock;
    // Since we do radix sort on each dataBlock at a time, the maxNumber of tuples in the dataBlock
    // is: LARGE_PAGE_SIZE / numBytesPerTuple.
    // The size of tmpTuplePtrSortingBlock should be larger than:
    // sizeof(uint8_t*) * MaxNumOfTuplePointers=(LARGE_PAGE_SIZE / numBytesPerTuple).
    // Since we know: numBytesPerTuple >= sizeof(uint8_t*) (note: we put the
    // tupleIdx/FactorizedTableIdx at the end of each row in dataBlock), sizeof(uint8_t*) *
    // MaxNumOfTuplePointers=(LARGE_PAGE_SIZE / numBytesPerTuple) <= LARGE_PAGE_SIZE. As a result,
    // we only need one dataBlock to store the tuplePointers while solving the string ties.
    std::unique_ptr<DataBlock> tmpTuplePtrSortingBlock;
    // FactorizedTable stores all columns in the tuples that will be sorted, including the order by
    // key columns. RadixSort uses factorizedTable to access the full contents of the string columns
    // when resolving ties.
    FactorizedTable& factorizedTable;
    std::vector<StrKeyColInfo> strKeyColsInfo;
    uint32_t numBytesPerTuple;
    uint32_t numBytesToRadixSort;
};

} // namespace processor
} // namespace lbug
