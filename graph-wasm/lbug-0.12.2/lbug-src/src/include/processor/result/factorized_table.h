#pragma once

#include <algorithm>
#include <numeric>

#include "common/in_mem_overflow_buffer.h"
#include "common/types/value/value.h"
#include "common/vector/value_vector.h"
#include "factorized_table_schema.h"
#include "flat_tuple.h"

namespace lbug {
namespace storage {
class MemoryManager;
}
namespace processor {

struct BlockAppendingInfo {
    BlockAppendingInfo(uint8_t* data, uint64_t numTuplesToAppend)
        : data{data}, numTuplesToAppend{numTuplesToAppend} {}

    uint8_t* data;
    uint64_t numTuplesToAppend;
};

// This struct allocates and holds one bmBackedBlock when constructed. The bmBackedBlock will be
// released when this struct goes out of scope.
class DataBlock {
public:
    DataBlock(storage::MemoryManager* mm, uint64_t size);
    ~DataBlock();

    uint8_t* getData() const;
    std::span<uint8_t> getSizedData() const;
    uint8_t* getWritableData() const;
    void resetNumTuplesAndFreeSize();
    void resetToZero();

    // Manually set the underlying memory buffer to evicted to avoid double free
    void preventDestruction();

    static void copyTuples(DataBlock* blockToCopyFrom, ft_tuple_idx_t tupleIdxToCopyFrom,
        DataBlock* blockToCopyInto, ft_tuple_idx_t tupleIdxToCopyTo, uint32_t numTuplesToCopy,
        uint32_t numBytesPerTuple);

public:
    uint32_t numTuples;
    uint64_t freeSize;

private:
    std::unique_ptr<storage::MemoryBuffer> block;
};

class DataBlockCollection {
public:
    // This interface is used for unFlat tuple blocks, for which numBytesPerTuple and
    // numTuplesPerBlock are useless.
    DataBlockCollection() : numBytesPerTuple{UINT32_MAX}, numTuplesPerBlock{UINT32_MAX} {}
    DataBlockCollection(uint32_t numBytesPerTuple, uint32_t numTuplesPerBlock)
        : numBytesPerTuple{numBytesPerTuple}, numTuplesPerBlock{numTuplesPerBlock} {}

    void append(std::unique_ptr<DataBlock> otherBlock) { blocks.push_back(std::move(otherBlock)); }
    void append(std::vector<std::unique_ptr<DataBlock>> otherBlocks) {
        std::move(begin(otherBlocks), end(otherBlocks), back_inserter(blocks));
    }
    void append(std::unique_ptr<DataBlockCollection> other) { append(std::move(other->blocks)); }
    bool needAllocation(uint64_t size) const { return isEmpty() || blocks.back()->freeSize < size; }

    bool isEmpty() const { return blocks.empty(); }
    const std::vector<std::unique_ptr<DataBlock>>& getBlocks() const { return blocks; }
    DataBlock* getBlock(ft_block_idx_t blockIdx) { return blocks[blockIdx].get(); }
    DataBlock* getLastBlock() { return blocks.back().get(); }

    void merge(DataBlockCollection& other);
    void preventDestruction() const {
        for (auto& block : blocks) {
            block->preventDestruction();
        }
    }

private:
    uint32_t numBytesPerTuple;
    uint32_t numTuplesPerBlock;
    std::vector<std::unique_ptr<DataBlock>> blocks;
};

class FlatTupleIterator;

class LBUG_API FactorizedTable {
    friend FlatTupleIterator;
    friend class JoinHashTable;
    friend class PathPropertyProbe;

public:
    FactorizedTable(storage::MemoryManager* memoryManager, FactorizedTableSchema tableSchema);
    ~FactorizedTable();
    void append(const std::vector<common::ValueVector*>& vectors);

    //! This function appends an empty tuple to the factorizedTable and returns a pointer to that
    //! tuple.
    uint8_t* appendEmptyTuple();

    // This function scans numTuplesToScan of rows to vectors starting at tupleIdx. Callers are
    // responsible for making sure all the parameters are valid.
    void scan(std::span<common::ValueVector*> vectors, ft_tuple_idx_t tupleIdx,
        uint64_t numTuplesToScan) const {
        std::vector<uint32_t> colIdxes(tableSchema.getNumColumns());
        iota(colIdxes.begin(), colIdxes.end(), 0);
        scan(vectors, tupleIdx, numTuplesToScan, colIdxes);
    }
    bool isEmpty() const { return getNumTuples() == 0; }
    void scan(std::span<common::ValueVector*> vectors, ft_tuple_idx_t tupleIdx,
        uint64_t numTuplesToScan, std::span<uint32_t> colIdxToScan) const;
    // TODO(Guodong): Unify these two interfaces along with `readUnflatCol`.
    // startPos is the starting position in the tuplesToRead, not the starting position in the
    // factorizedTable
    void lookup(std::span<common::ValueVector*> vectors, std::span<uint32_t> colIdxesToScan,
        uint8_t** tuplesToRead, uint64_t startPos, uint64_t numTuplesToRead) const;
    void lookup(std::vector<common::ValueVector*>& vectors,
        const common::SelectionVector* selVector, std::vector<uint32_t>& colIdxesToScan,
        uint8_t* tupleToRead) const;
    void lookup(std::vector<common::ValueVector*>& vectors, std::vector<uint32_t>& colIdxesToScan,
        std::vector<ft_tuple_idx_t>& tupleIdxesToRead, uint64_t startPos,
        uint64_t numTuplesToRead) const;

    // When we merge two factorizedTables, we need to update the hasNoNullGuarantee based on
    // other factorizedTable.
    void mergeMayContainNulls(FactorizedTable& other);
    void merge(FactorizedTable& other);

    common::InMemOverflowBuffer* getInMemOverflowBuffer() const {
        return inMemOverflowBuffer.get();
    }

    bool hasUnflatCol() const;
    bool hasUnflatCol(std::vector<ft_col_idx_t>& colIdxes) const {
        return std::any_of(colIdxes.begin(), colIdxes.end(),
            [this](ft_col_idx_t colIdx) { return !tableSchema.getColumn(colIdx)->isFlat(); });
    }

    uint64_t getNumTuples() const { return numTuples; }
    uint64_t getTotalNumFlatTuples() const;
    uint64_t getNumFlatTuples(ft_tuple_idx_t tupleIdx) const;

    const std::vector<std::unique_ptr<DataBlock>>& getTupleDataBlocks() {
        return flatTupleBlockCollection->getBlocks();
    }
    const FactorizedTableSchema* getTableSchema() const { return &tableSchema; }

    template<typename TYPE>
    TYPE getData(ft_block_idx_t blockIdx, ft_block_offset_t blockOffset,
        ft_col_offset_t colOffset) const {
        return *((TYPE*)getCell(blockIdx, blockOffset, colOffset));
    }

    uint8_t* getTuple(ft_tuple_idx_t tupleIdx) const;

    void updateFlatCell(uint8_t* tuplePtr, ft_col_idx_t colIdx, common::ValueVector* valueVector,
        uint32_t pos);
    void updateFlatCellNoNull(uint8_t* ftTuplePtr, ft_col_idx_t colIdx, void* dataBuf) {
        memcpy(ftTuplePtr + tableSchema.getColOffset(colIdx), dataBuf,
            tableSchema.getColumn(colIdx)->getNumBytes());
    }

    uint64_t getNumTuplesPerBlock() const { return numFlatTuplesPerBlock; }

    bool hasNoNullGuarantee(ft_col_idx_t colIdx) const {
        return tableSchema.getColumn(colIdx)->hasNoNullGuarantee();
    }

    bool isOverflowColNull(const uint8_t* nullBuffer, ft_tuple_idx_t tupleIdx,
        ft_col_idx_t colIdx) const;
    bool isNonOverflowColNull(const uint8_t* nullBuffer, ft_col_idx_t colIdx) const;
    bool isNonOverflowColNull(ft_tuple_idx_t tupleIdx, ft_col_idx_t colIdx) const;
    void setNonOverflowColNull(uint8_t* nullBuffer, ft_col_idx_t colIdx);
    void clear();

    storage::MemoryManager* getMemoryManager() { return memoryManager; }

    void resize(uint64_t numTuples);

    template<typename Func>
    void forEach(Func func) {
        for (auto& tupleBlock : flatTupleBlockCollection->getBlocks()) {
            uint8_t* tuple = tupleBlock->getData();
            for (auto i = 0u; i < tupleBlock->numTuples; i++) {
                func(tuple);
                tuple += getTableSchema()->getNumBytesPerTuple();
            }
        }
    }

    static std::shared_ptr<FactorizedTable> EmptyTable(storage::MemoryManager* mm) {
        return std::make_shared<FactorizedTable>(mm, FactorizedTableSchema());
    }

    void setPreventDestruction(bool preventDestruction) {
        this->preventDestruction = preventDestruction;
    }

private:
    void setOverflowColNull(uint8_t* nullBuffer, ft_col_idx_t colIdx, ft_tuple_idx_t tupleIdx);

    uint64_t computeNumTuplesToAppend(
        const std::vector<common::ValueVector*>& vectorsToAppend) const;

    uint8_t* getCell(ft_block_idx_t blockIdx, ft_block_offset_t blockOffset,
        ft_col_offset_t colOffset) const {
        return flatTupleBlockCollection->getBlock(blockIdx)->getData() +
               blockOffset * tableSchema.getNumBytesPerTuple() + colOffset;
    }
    std::pair<ft_block_idx_t, ft_block_offset_t> getBlockIdxAndTupleIdxInBlock(
        uint64_t tupleIdx) const {
        return std::make_pair(tupleIdx / numFlatTuplesPerBlock, tupleIdx % numFlatTuplesPerBlock);
    }

    std::vector<BlockAppendingInfo> allocateFlatTupleBlocks(uint64_t numTuplesToAppend);
    uint8_t* allocateUnflatTupleBlock(uint32_t numBytes);
    void copyFlatVectorToFlatColumn(const common::ValueVector& vector,
        const BlockAppendingInfo& blockAppendInfo, ft_col_idx_t colIdx);
    void copyUnflatVectorToFlatColumn(const common::ValueVector& vector,
        const BlockAppendingInfo& blockAppendInfo, uint64_t numAppendedTuples, ft_col_idx_t colIdx);
    void copyVectorToFlatColumn(const common::ValueVector& vector,
        const BlockAppendingInfo& blockAppendInfo, uint64_t numAppendedTuples,
        ft_col_idx_t colIdx) {
        vector.state->isFlat() ?
            copyFlatVectorToFlatColumn(vector, blockAppendInfo, colIdx) :
            copyUnflatVectorToFlatColumn(vector, blockAppendInfo, numAppendedTuples, colIdx);
    }
    void copyVectorToUnflatColumn(const common::ValueVector& vector,
        const BlockAppendingInfo& blockAppendInfo, ft_col_idx_t colIdx);
    void copyVectorToColumn(const common::ValueVector& vector,
        const BlockAppendingInfo& blockAppendInfo, uint64_t numAppendedTuples, ft_col_idx_t colIdx);
    common::overflow_value_t appendVectorToUnflatTupleBlocks(const common::ValueVector& vector,
        ft_col_idx_t colIdx);

    // TODO(Guodong): Unify these two `readUnflatCol()` with a (possibly templated) copy executor.
    void readUnflatCol(uint8_t** tuplesToRead, ft_col_idx_t colIdx,
        common::ValueVector& vector) const;
    void readUnflatCol(const uint8_t* tupleToRead, const common::SelectionVector& selVector,
        ft_col_idx_t colIdx, common::ValueVector& vector) const;
    void readFlatColToFlatVector(uint8_t* tupleToRead, ft_col_idx_t colIdx,
        common::ValueVector& vector, common::sel_t pos) const;
    void readFlatColToUnflatVector(uint8_t** tuplesToRead, ft_col_idx_t colIdx,
        common::ValueVector& vector, uint64_t numTuplesToRead) const;
    void readFlatCol(uint8_t** tuplesToRead, ft_col_idx_t colIdx, common::ValueVector& vector,
        uint64_t numTuplesToRead) const;

private:
    storage::MemoryManager* memoryManager;
    // Table Schema. Keeping track of factorization structure.
    FactorizedTableSchema tableSchema;
    // Number of rows in table.
    uint64_t numTuples;
    // Radix sort requires there is a fixed number of tuple in a block.
    uint64_t flatTupleBlockSize;
    uint32_t numFlatTuplesPerBlock;
    // Data blocks for flat tuples.
    std::unique_ptr<DataBlockCollection> flatTupleBlockCollection;
    // Data blocks for unFlat tuples.
    std::unique_ptr<DataBlockCollection> unFlatTupleBlockCollection;
    // Overflow buffer storing variable size part of an entry.
    std::unique_ptr<common::InMemOverflowBuffer> inMemOverflowBuffer;
    // Prevent destruction of the underlying data structures when the factorized table is
    // destructed. If the parent database is closed, the underlying data structures is already
    // destructed, so destruction will cause double free.
    bool preventDestruction = false;
};

class FactorizedTableIterator {
public:
    explicit FactorizedTableIterator(FactorizedTable& factorizedTable);

    bool hasNext() {
        return nextTupleIdx < factorizedTable.getNumTuples() || nextFlatTupleIdx < numFlatTuples;
    }

    void getNext(FlatTuple& tuple);

    void resetState();

private:
    // The dataChunkPos may be not consecutive, which means some entries in the
    // flatTuplePositionsInDataChunk is invalid. We put pair(UINT64_MAX, UINT64_MAX) in the
    // invalid entries.
    bool isValidDataChunkPos(uint32_t dataChunkPos) const {
        return flatTuplePositionsInDataChunk[dataChunkPos].first != UINT64_MAX;
    }

    void readUnflatColToFlatTuple(ft_col_idx_t colIdx, uint8_t* valueBuffer, FlatTuple& tuple);

    void readFlatColToFlatTuple(ft_col_idx_t colIdx, uint8_t* valueBuffer, FlatTuple& tuple);

    // We put pair(UINT64_MAX, UINT64_MAX) in all invalid entries in
    // FlatTuplePositionsInDataChunk.
    void updateInvalidEntriesInFlatTuplePositionsInDataChunk();

    // This function is used to update the number of elements in the dataChunk when we want
    // to iterate a new tuple.
    void updateNumElementsInDataChunk();

    // This function updates the flatTuplePositionsInDataChunk, so that getNextFlatTuple() can
    // correctly outputs the next flat tuple in the current tuple. For example, we want to read
    // two unFlat columns, which are on different dataChunks A,B and both have 100 columns. The
    // flatTuplePositionsInDataChunk after the first call to getNextFlatTuple() looks like:
    // {dataChunkA : [0, 100]}, {dataChunkB : [0, 100]} This function updates the
    // flatTuplePositionsInDataChunk to: {dataChunkA: [1, 100]}, {dataChunkB: [0, 100]}. Meaning
    // that the getNextFlatTuple() should read the second element in the first unflat column and
    // the first element in the second unflat column.
    void updateFlatTuplePositionsInDataChunk();

    const FactorizedTable& factorizedTable;
    uint8_t* currentTupleBuffer;
    uint64_t numFlatTuples;
    ft_tuple_idx_t nextFlatTupleIdx;
    ft_tuple_idx_t nextTupleIdx;
    // This field stores the (nextIdxToReadInDataChunk, numElementsInDataChunk) of each dataChunk.
    std::vector<std::pair<uint64_t, uint64_t>> flatTuplePositionsInDataChunk;
};

} // namespace processor
} // namespace lbug
