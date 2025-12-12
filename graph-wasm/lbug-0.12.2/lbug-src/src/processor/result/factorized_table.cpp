#include "processor/result/factorized_table.h"

#include <cstdint>

#include "common/assert.h"
#include "common/exception/runtime.h"
#include "common/null_buffer.h"
#include "common/vector/value_vector.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

DataBlock::DataBlock(storage::MemoryManager* mm, uint64_t size) : numTuples{0}, freeSize{size} {
    block = mm->allocateBuffer(true /* initializeToZero */, size);
}

DataBlock::~DataBlock() = default;

uint8_t* DataBlock::getData() const {
    return block->getBuffer().data();
}
std::span<uint8_t> DataBlock::getSizedData() const {
    return block->getBuffer();
}
uint8_t* DataBlock::getWritableData() const {
    return block->getBuffer().last(freeSize).data();
}
void DataBlock::resetNumTuplesAndFreeSize() {
    freeSize = block->getBuffer().size();
    numTuples = 0;
}
void DataBlock::resetToZero() {
    memset(block->getBuffer().data(), 0, block->getBuffer().size());
}

void DataBlock::preventDestruction() {
    block->preventDestruction();
}

void DataBlock::copyTuples(DataBlock* blockToCopyFrom, ft_tuple_idx_t tupleIdxToCopyFrom,
    DataBlock* blockToCopyInto, ft_tuple_idx_t tupleIdxToCopyTo, uint32_t numTuplesToCopy,
    uint32_t numBytesPerTuple) {
    for (auto i = 0u; i < numTuplesToCopy; i++) {
        memcpy(blockToCopyInto->getData() + (tupleIdxToCopyTo * numBytesPerTuple),
            blockToCopyFrom->getData() + (tupleIdxToCopyFrom * numBytesPerTuple), numBytesPerTuple);
        tupleIdxToCopyFrom++;
        tupleIdxToCopyTo++;
    }
    blockToCopyInto->numTuples += numTuplesToCopy;
    blockToCopyInto->freeSize -= (numTuplesToCopy * numBytesPerTuple);
}

void DataBlockCollection::merge(DataBlockCollection& other) {
    if (blocks.empty()) {
        append(std::move(other.blocks));
        return;
    }
    // Pop up the old last block first, and then push back blocks from other into the vector.
    auto oldLastBlock = std::move(blocks.back());
    blocks.pop_back();
    append(std::move(other.blocks));
    // Insert back tuples in the old last block to the new last block.
    auto newLastBlock = blocks.back().get();
    auto numTuplesToAppendIntoNewLastBlock =
        std::min(numTuplesPerBlock - newLastBlock->numTuples, oldLastBlock->numTuples);
    DataBlock::copyTuples(oldLastBlock.get(), 0, newLastBlock, newLastBlock->numTuples,
        numTuplesToAppendIntoNewLastBlock, numBytesPerTuple);
    // If any tuples left in the old last block, shift them to the beginning, and push the old last
    // block back.
    auto numTuplesLeftForNewBlock = oldLastBlock->numTuples - numTuplesToAppendIntoNewLastBlock;
    if (numTuplesLeftForNewBlock > 0) {
        auto tupleIdxInOldLastBlock = numTuplesToAppendIntoNewLastBlock;
        oldLastBlock->resetNumTuplesAndFreeSize();
        DataBlock::copyTuples(oldLastBlock.get(), tupleIdxInOldLastBlock, oldLastBlock.get(), 0,
            numTuplesLeftForNewBlock, numBytesPerTuple);
        blocks.push_back(std::move(oldLastBlock));
    }
}

FactorizedTable::FactorizedTable(MemoryManager* memoryManager, FactorizedTableSchema tableSchema)
    : memoryManager{memoryManager}, tableSchema{std::move(tableSchema)}, numTuples{0} {
    if (!this->tableSchema.isEmpty()) {
        inMemOverflowBuffer = std::make_unique<InMemOverflowBuffer>(memoryManager);
        auto numBytesPerTuple = this->tableSchema.getNumBytesPerTuple();
        if (numBytesPerTuple > TEMP_PAGE_SIZE) {
            // I realize it's unlikely to trigger this case because the fixed size part for
            // a column is always small. A quick calculation, assume average column size is 16 bytes
            // then we need more than 16K column to test this. I choose to throw exception until
            // we encounter a use case.
            throw RuntimeException(
                "Trying to allocate for a large tuple of size greater than 256KB. "
                "Allocation is disabled for performance reason.");
        }
        flatTupleBlockSize = TEMP_PAGE_SIZE;
        numFlatTuplesPerBlock = flatTupleBlockSize / numBytesPerTuple;
        flatTupleBlockCollection =
            std::make_unique<DataBlockCollection>(numBytesPerTuple, numFlatTuplesPerBlock);
        unFlatTupleBlockCollection = std::make_unique<DataBlockCollection>();
    }
}

FactorizedTable::~FactorizedTable() {
    if (!preventDestruction) {
        return;
    }
    flatTupleBlockCollection->preventDestruction();
    unFlatTupleBlockCollection->preventDestruction();
    inMemOverflowBuffer->preventDestruction();
}

void FactorizedTable::append(const std::vector<ValueVector*>& vectors) {
    auto numTuplesToAppend = computeNumTuplesToAppend(vectors);
    auto appendInfos = allocateFlatTupleBlocks(numTuplesToAppend);
    for (auto i = 0u; i < vectors.size(); i++) {
        auto numAppendedTuples = 0ul;
        for (auto& blockAppendInfo : appendInfos) {
            copyVectorToColumn(*vectors[i], blockAppendInfo, numAppendedTuples, i);
            numAppendedTuples += blockAppendInfo.numTuplesToAppend;
        }
        KU_ASSERT(numAppendedTuples == numTuplesToAppend);
    }
    numTuples += numTuplesToAppend;
}

void FactorizedTable::resize(uint64_t numTuples) {
    if (numTuples > this->numTuples) {
        auto numTuplesToAdd = numTuples - this->numTuples;
        auto numBytesPerTuple = tableSchema.getNumBytesPerTuple();
        while (flatTupleBlockCollection->needAllocation(numTuplesToAdd * numBytesPerTuple)) {
            auto newBlock = std::make_unique<DataBlock>(memoryManager, flatTupleBlockSize);
            flatTupleBlockCollection->append(std::move(newBlock));
            auto numTuplesToAddInBlock =
                std::min(static_cast<uint32_t>(numTuplesToAdd), numFlatTuplesPerBlock);
            auto block = flatTupleBlockCollection->getLastBlock();
            block->freeSize -= numBytesPerTuple * numTuplesToAddInBlock;
            block->numTuples += numTuplesToAddInBlock;
            numTuplesToAdd -= numTuplesToAddInBlock;
        }
        KU_ASSERT(numTuplesToAdd < numFlatTuplesPerBlock);
        auto block = flatTupleBlockCollection->getLastBlock();
        block->freeSize -= numBytesPerTuple * numTuplesToAdd;
        block->numTuples += numTuplesToAdd;
    } else {
        auto numTuplesRemaining = numTuples;
        KU_ASSERT(flatTupleBlockCollection->getBlocks().size() == 1);
        // TODO: It always adds to the end, so this will leave empty blocks in the middle if it's
        // reused
        for (auto& block : flatTupleBlockCollection->getBlocks()) {
            block->numTuples =
                std::min(static_cast<uint32_t>(numTuplesRemaining), numFlatTuplesPerBlock);
            block->freeSize =
                block->getSizedData().size() - block->numTuples * tableSchema.getNumBytesPerTuple();
            numTuplesRemaining -= block->numTuples;
        }
        KU_ASSERT(numTuplesRemaining == 0);
    }
    this->numTuples = numTuples;
}
uint8_t* FactorizedTable::appendEmptyTuple() {
    auto numBytesPerTuple = tableSchema.getNumBytesPerTuple();
    if (flatTupleBlockCollection->needAllocation(numBytesPerTuple)) {
        auto newBlock = std::make_unique<DataBlock>(memoryManager, flatTupleBlockSize);
        flatTupleBlockCollection->append(std::move(newBlock));
    }
    auto block = flatTupleBlockCollection->getLastBlock();
    uint8_t* tuplePtr = block->getWritableData();
    block->freeSize -= numBytesPerTuple;
    block->numTuples++;
    numTuples++;
    return tuplePtr;
}

void FactorizedTable::scan(std::span<ValueVector*> vectors, ft_tuple_idx_t tupleIdx,
    uint64_t numTuplesToScan, std::span<ft_col_idx_t> colIdxesToScan) const {
    KU_ASSERT(tupleIdx + numTuplesToScan <= numTuples);
    KU_ASSERT(vectors.size() == colIdxesToScan.size());
    std::unique_ptr<uint8_t*[]> tuplesToRead = std::make_unique<uint8_t*[]>(numTuplesToScan);
    for (auto i = 0u; i < numTuplesToScan; i++) {
        tuplesToRead[i] = getTuple(tupleIdx + i);
    }
    lookup(vectors, colIdxesToScan, tuplesToRead.get(), 0 /* startPos */, numTuplesToScan);
}

void FactorizedTable::lookup(std::span<ValueVector*> vectors,
    std::span<ft_col_idx_t> colIdxesToScan, uint8_t** tuplesToRead, uint64_t startPos,
    uint64_t numTuplesToRead) const {
    KU_ASSERT(vectors.size() == colIdxesToScan.size());
    for (auto i = 0u; i < colIdxesToScan.size(); i++) {
        auto vector = vectors[i];
        // TODO(Xiyang/Ziyi): we should set up a rule about when to reset. Should it be in operator?
        vector->resetAuxiliaryBuffer();
        ft_col_idx_t colIdx = colIdxesToScan[i];
        if (tableSchema.getColumn(colIdx)->isFlat()) {
            KU_ASSERT(!(vector->state->isFlat() && numTuplesToRead > 1));
            readFlatCol(tuplesToRead + startPos, colIdx, *vector, numTuplesToRead);
        } else {
            // If the caller wants to read an unflat column from factorizedTable, the vector
            // must be unflat and the numTuplesToScan should be 1.
            KU_ASSERT(!vector->state->isFlat() && numTuplesToRead == 1);
            readUnflatCol(tuplesToRead + startPos, colIdx, *vector);
        }
    }
}

void FactorizedTable::lookup(std::vector<ValueVector*>& vectors, const SelectionVector* selVector,
    std::vector<ft_col_idx_t>& colIdxesToScan, uint8_t* tupleToRead) const {
    KU_ASSERT(vectors.size() == colIdxesToScan.size());
    for (auto i = 0u; i < colIdxesToScan.size(); i++) {
        ft_col_idx_t colIdx = colIdxesToScan[i];
        if (tableSchema.getColumn(colIdx)->isFlat()) {
            readFlatCol(&tupleToRead, colIdx, *vectors[i], 1);
        } else {
            readUnflatCol(tupleToRead, *selVector, colIdx, *vectors[i]);
        }
    }
}

void FactorizedTable::lookup(std::vector<ValueVector*>& vectors,
    std::vector<ft_col_idx_t>& colIdxesToScan, std::vector<ft_tuple_idx_t>& tupleIdxesToRead,
    uint64_t startPos, uint64_t numTuplesToRead) const {
    KU_ASSERT(vectors.size() == colIdxesToScan.size());
    auto tuplesToRead = std::make_unique<uint8_t*[]>(tupleIdxesToRead.size());
    KU_ASSERT(numTuplesToRead > 0);
    for (auto i = 0u; i < numTuplesToRead; i++) {
        tuplesToRead[i] = getTuple(tupleIdxesToRead[i + startPos]);
    }
    lookup(vectors, colIdxesToScan, tuplesToRead.get(), 0 /* startPos */, numTuplesToRead);
}

void FactorizedTable::mergeMayContainNulls(FactorizedTable& other) {
    for (auto i = 0u; i < other.tableSchema.getNumColumns(); i++) {
        if (!other.hasNoNullGuarantee(i)) {
            tableSchema.setMayContainsNullsToTrue(i);
        }
    }
}

void FactorizedTable::merge(FactorizedTable& other) {
    KU_ASSERT(tableSchema == other.tableSchema);
    if (other.numTuples == 0) {
        return;
    }
    mergeMayContainNulls(other);
    unFlatTupleBlockCollection->append(std::move(other.unFlatTupleBlockCollection));
    flatTupleBlockCollection->merge(*other.flatTupleBlockCollection);
    inMemOverflowBuffer->merge(*other.inMemOverflowBuffer);
    numTuples += other.numTuples;
}

bool FactorizedTable::hasUnflatCol() const {
    std::vector<ft_col_idx_t> colIdxes(tableSchema.getNumColumns());
    iota(colIdxes.begin(), colIdxes.end(), 0);
    return hasUnflatCol(colIdxes);
}

uint64_t FactorizedTable::getTotalNumFlatTuples() const {
    auto totalNumFlatTuples = 0ul;
    for (auto i = 0u; i < getNumTuples(); i++) {
        totalNumFlatTuples += getNumFlatTuples(i);
    }
    return totalNumFlatTuples;
}

uint64_t FactorizedTable::getNumFlatTuples(ft_tuple_idx_t tupleIdx) const {
    std::unordered_map<uint32_t, bool> calculatedGroups;
    uint64_t numFlatTuples = 1;
    auto tupleBuffer = getTuple(tupleIdx);
    for (auto i = 0u; i < tableSchema.getNumColumns(); i++) {
        auto column = tableSchema.getColumn(i);
        auto groupID = column->getGroupID();
        if (!calculatedGroups.contains(groupID)) {
            calculatedGroups[groupID] = true;
            numFlatTuples *= column->isFlat() ? 1 : ((overflow_value_t*)tupleBuffer)->numElements;
        }
        tupleBuffer += column->getNumBytes();
    }
    return numFlatTuples;
}

uint8_t* FactorizedTable::getTuple(ft_tuple_idx_t tupleIdx) const {
    KU_ASSERT(tupleIdx < numTuples);
    auto [blockIdx, tupleIdxInBlock] = getBlockIdxAndTupleIdxInBlock(tupleIdx);
    auto buffer = flatTupleBlockCollection->getBlock(blockIdx)->getSizedData();
    // Check that the end of the block doesn't overflow the buffer
    KU_ASSERT((tupleIdxInBlock + 1) * tableSchema.getNumBytesPerTuple() <= buffer.size());
    return buffer.data() + tupleIdxInBlock * tableSchema.getNumBytesPerTuple();
}

void FactorizedTable::updateFlatCell(uint8_t* tuplePtr, ft_col_idx_t colIdx,
    ValueVector* valueVector, uint32_t pos) {
    auto nullBuffer = tuplePtr + tableSchema.getNullMapOffset();
    if (valueVector->isNull(pos)) {
        setNonOverflowColNull(nullBuffer, colIdx);
    } else {
        valueVector->copyToRowData(pos, tuplePtr + tableSchema.getColOffset(colIdx),
            inMemOverflowBuffer.get());
        NullBuffer::setNoNull(nullBuffer, colIdx);
    }
}

bool FactorizedTable::isOverflowColNull(const uint8_t* nullBuffer, ft_tuple_idx_t tupleIdx,
    ft_col_idx_t colIdx) const {
    KU_ASSERT(colIdx < tableSchema.getNumColumns());
    if (tableSchema.getColumn(colIdx)->hasNoNullGuarantee()) {
        return false;
    }
    return NullBuffer::isNull(nullBuffer, tupleIdx);
}

bool FactorizedTable::isNonOverflowColNull(const uint8_t* nullBuffer, ft_col_idx_t colIdx) const {
    KU_ASSERT(colIdx < tableSchema.getNumColumns());
    if (tableSchema.getColumn(colIdx)->hasNoNullGuarantee()) {
        return false;
    }
    return NullBuffer::isNull(nullBuffer, colIdx);
}

bool FactorizedTable::isNonOverflowColNull(ft_tuple_idx_t tupleIdx, ft_col_idx_t colIdx) const {
    KU_ASSERT(colIdx < tableSchema.getNumColumns());
    if (tableSchema.getColumn(colIdx)->hasNoNullGuarantee()) {
        return false;
    }
    return NullBuffer::isNull(getTuple(tupleIdx) + tableSchema.getNullMapOffset(), colIdx);
}

void FactorizedTable::setNonOverflowColNull(uint8_t* nullBuffer, ft_col_idx_t colIdx) {
    NullBuffer::setNull(nullBuffer, colIdx);
    tableSchema.setMayContainsNullsToTrue(colIdx);
}

void FactorizedTable::clear() {
    numTuples = 0;
    flatTupleBlockCollection = std::make_unique<DataBlockCollection>(
        tableSchema.getNumBytesPerTuple(), numFlatTuplesPerBlock);
    unFlatTupleBlockCollection = std::make_unique<DataBlockCollection>();
    inMemOverflowBuffer->resetBuffer();
}

void FactorizedTable::setOverflowColNull(uint8_t* nullBuffer, ft_col_idx_t colIdx,
    ft_tuple_idx_t tupleIdx) {
    NullBuffer::setNull(nullBuffer, tupleIdx);
    tableSchema.setMayContainsNullsToTrue(colIdx);
}

// TODO(Guodong): change this function to not use dataChunkPos in ColumnSchema.
uint64_t FactorizedTable::computeNumTuplesToAppend(
    const std::vector<ValueVector*>& vectorsToAppend) const {
    KU_ASSERT(!vectorsToAppend.empty());
    auto numTuplesToAppend = 1ul;
    for (auto i = 0u; i < vectorsToAppend.size(); i++) {
        // If the caller tries to append an unflat vector to a flat column in the
        // factorizedTable, the factorizedTable needs to flatten that vector.
        if (tableSchema.getColumn(i)->isFlat() && !vectorsToAppend[i]->state->isFlat()) {
            // The caller is not allowed to append multiple unFlat columns from different
            // datachunks to multiple flat columns in the factorizedTable.
            numTuplesToAppend = vectorsToAppend[i]->state->getSelVector().getSelSize();
        }
    }
    return numTuplesToAppend;
}

std::vector<BlockAppendingInfo> FactorizedTable::allocateFlatTupleBlocks(
    uint64_t numTuplesToAppend) {
    auto numBytesPerTuple = tableSchema.getNumBytesPerTuple();
    std::vector<BlockAppendingInfo> appendingInfos;
    while (numTuplesToAppend > 0) {
        if (flatTupleBlockCollection->needAllocation(numBytesPerTuple)) {
            auto newBlock = std::make_unique<DataBlock>(memoryManager, flatTupleBlockSize);
            flatTupleBlockCollection->append(std::move(newBlock));
        }
        auto block = flatTupleBlockCollection->getLastBlock();
        auto numTuplesToAppendInCurBlock =
            std::min(numTuplesToAppend, block->freeSize / numBytesPerTuple);
        appendingInfos.emplace_back(block->getWritableData(), numTuplesToAppendInCurBlock);
        block->freeSize -= numTuplesToAppendInCurBlock * numBytesPerTuple;
        block->numTuples += numTuplesToAppendInCurBlock;
        numTuplesToAppend -= numTuplesToAppendInCurBlock;
    }
    return appendingInfos;
}

uint64_t getDataBlockSize(uint32_t numBytes) {
    if (numBytes < TEMP_PAGE_SIZE) {
        return TEMP_PAGE_SIZE;
    }
    return numBytes + 1;
}

uint8_t* FactorizedTable::allocateUnflatTupleBlock(uint32_t numBytes) {
    if (unFlatTupleBlockCollection->isEmpty()) {
        auto newBlock = std::make_unique<DataBlock>(memoryManager, getDataBlockSize(numBytes));
        unFlatTupleBlockCollection->append(std::move(newBlock));
    }
    auto lastBlock = unFlatTupleBlockCollection->getLastBlock();
    if (lastBlock->freeSize > numBytes) {
        auto writableData = lastBlock->getWritableData();
        lastBlock->freeSize -= numBytes;
        return writableData;
    }
    auto newBlock = std::make_unique<DataBlock>(memoryManager, getDataBlockSize(numBytes));
    unFlatTupleBlockCollection->append(std::move(newBlock));
    lastBlock = unFlatTupleBlockCollection->getLastBlock();
    lastBlock->freeSize -= numBytes;
    return lastBlock->getData();
}

void FactorizedTable::copyFlatVectorToFlatColumn(const ValueVector& vector,
    const BlockAppendingInfo& blockAppendInfo, ft_col_idx_t colIdx) {
    auto valuePositionInVectorToAppend = vector.state->getSelVector()[0];
    auto colOffsetInDataBlock = tableSchema.getColOffset(colIdx);
    auto dstDataPtr = blockAppendInfo.data;
    for (auto i = 0u; i < blockAppendInfo.numTuplesToAppend; i++) {
        if (vector.isNull(valuePositionInVectorToAppend)) {
            setNonOverflowColNull(dstDataPtr + tableSchema.getNullMapOffset(), colIdx);
        } else {
            vector.copyToRowData(valuePositionInVectorToAppend, dstDataPtr + colOffsetInDataBlock,
                inMemOverflowBuffer.get());
        }
        dstDataPtr += tableSchema.getNumBytesPerTuple();
    }
}

void FactorizedTable::copyUnflatVectorToFlatColumn(const ValueVector& vector,
    const BlockAppendingInfo& blockAppendInfo, uint64_t numAppendedTuples, ft_col_idx_t colIdx) {
    auto byteOffsetOfColumnInTuple = tableSchema.getColOffset(colIdx);
    auto dstTuple = blockAppendInfo.data;
    if (vector.state->getSelVector().isUnfiltered()) {
        if (vector.hasNoNullsGuarantee()) {
            for (auto i = 0u; i < blockAppendInfo.numTuplesToAppend; i++) {
                vector.copyToRowData(numAppendedTuples + i, dstTuple + byteOffsetOfColumnInTuple,
                    inMemOverflowBuffer.get());
                dstTuple += tableSchema.getNumBytesPerTuple();
            }
        } else {
            for (auto i = 0u; i < blockAppendInfo.numTuplesToAppend; i++) {
                if (vector.isNull(numAppendedTuples + i)) {
                    setNonOverflowColNull(dstTuple + tableSchema.getNullMapOffset(), colIdx);
                } else {
                    vector.copyToRowData(numAppendedTuples + i,
                        dstTuple + byteOffsetOfColumnInTuple, inMemOverflowBuffer.get());
                }
                dstTuple += tableSchema.getNumBytesPerTuple();
            }
        }
    } else {
        if (vector.hasNoNullsGuarantee()) {
            for (auto i = 0u; i < blockAppendInfo.numTuplesToAppend; i++) {
                vector.copyToRowData(vector.state->getSelVector()[numAppendedTuples + i],
                    dstTuple + byteOffsetOfColumnInTuple, inMemOverflowBuffer.get());
                dstTuple += tableSchema.getNumBytesPerTuple();
            }
        } else {
            for (auto i = 0u; i < blockAppendInfo.numTuplesToAppend; i++) {
                auto pos = vector.state->getSelVector()[numAppendedTuples + i];
                if (vector.isNull(pos)) {
                    setNonOverflowColNull(dstTuple + tableSchema.getNullMapOffset(), colIdx);
                } else {
                    vector.copyToRowData(pos, dstTuple + byteOffsetOfColumnInTuple,
                        inMemOverflowBuffer.get());
                }
                dstTuple += tableSchema.getNumBytesPerTuple();
            }
        }
    }
}

// For an unflat column, only an unflat vector is allowed to copy from, for the column, we only
// store an overflow_value_t, which contains a pointer to the overflow dataBlock in the
// factorizedTable. NullMasks are stored inside the overflow buffer.
void FactorizedTable::copyVectorToUnflatColumn(const ValueVector& vector,
    const BlockAppendingInfo& blockAppendInfo, ft_col_idx_t colIdx) {
    KU_ASSERT(!vector.state->isFlat());
    auto unflatTupleValue = appendVectorToUnflatTupleBlocks(vector, colIdx);
    auto blockPtr = blockAppendInfo.data + tableSchema.getColOffset(colIdx);
    for (auto i = 0u; i < blockAppendInfo.numTuplesToAppend; i++) {
        memcpy(blockPtr, (uint8_t*)&unflatTupleValue, sizeof(overflow_value_t));
        blockPtr += tableSchema.getNumBytesPerTuple();
    }
}

void FactorizedTable::copyVectorToColumn(const ValueVector& vector,
    const BlockAppendingInfo& blockAppendInfo, uint64_t numAppendedTuples, ft_col_idx_t colIdx) {
    if (tableSchema.getColumn(colIdx)->isFlat()) {
        copyVectorToFlatColumn(vector, blockAppendInfo, numAppendedTuples, colIdx);
    } else {
        copyVectorToUnflatColumn(vector, blockAppendInfo, colIdx);
    }
}

overflow_value_t FactorizedTable::appendVectorToUnflatTupleBlocks(const ValueVector& vector,
    ft_col_idx_t colIdx) {
    KU_ASSERT(!vector.state->isFlat());
    auto numFlatTuplesInVector = vector.state->getSelVector().getSelSize();
    auto numBytesPerValue = LogicalTypeUtils::getRowLayoutSize(vector.dataType);
    auto numBytesForData = numBytesPerValue * numFlatTuplesInVector;
    auto overflowBlockBuffer = allocateUnflatTupleBlock(
        numBytesForData + NullBuffer::getNumBytesForNullValues(numFlatTuplesInVector));
    if (vector.state->getSelVector().isUnfiltered()) {
        if (vector.hasNoNullsGuarantee()) {
            auto dstDataBuffer = overflowBlockBuffer;
            for (auto i = 0u; i < numFlatTuplesInVector; i++) {
                vector.copyToRowData(i, dstDataBuffer, inMemOverflowBuffer.get());
                dstDataBuffer += numBytesPerValue;
            }
        } else {
            auto dstDataBuffer = overflowBlockBuffer;
            for (auto i = 0u; i < numFlatTuplesInVector; i++) {
                if (vector.isNull(i)) {
                    setOverflowColNull(overflowBlockBuffer + numBytesForData, colIdx, i);
                } else {
                    vector.copyToRowData(i, dstDataBuffer, inMemOverflowBuffer.get());
                }
                dstDataBuffer += numBytesPerValue;
            }
        }
    } else {
        if (vector.hasNoNullsGuarantee()) {
            auto dstDataBuffer = overflowBlockBuffer;
            for (auto i = 0u; i < numFlatTuplesInVector; i++) {
                vector.copyToRowData(vector.state->getSelVector()[i], dstDataBuffer,
                    inMemOverflowBuffer.get());
                dstDataBuffer += numBytesPerValue;
            }
        } else {
            auto dstDataBuffer = overflowBlockBuffer;
            for (auto i = 0u; i < numFlatTuplesInVector; i++) {
                auto pos = vector.state->getSelVector()[i];
                if (vector.isNull(pos)) {
                    setOverflowColNull(overflowBlockBuffer + numBytesForData, colIdx, i);
                } else {
                    vector.copyToRowData(pos, dstDataBuffer, inMemOverflowBuffer.get());
                }
                dstDataBuffer += numBytesPerValue;
            }
        }
    }
    return overflow_value_t{numFlatTuplesInVector, overflowBlockBuffer};
}

void FactorizedTable::readUnflatCol(uint8_t** tuplesToRead, ft_col_idx_t colIdx,
    ValueVector& vector) const {
    auto overflowColValue =
        *(overflow_value_t*)(tuplesToRead[0] + tableSchema.getColOffset(colIdx));
    KU_ASSERT(vector.state->getSelVector().isUnfiltered());
    auto numBytesPerValue = LogicalTypeUtils::getRowLayoutSize(vector.dataType);
    if (hasNoNullGuarantee(colIdx)) {
        vector.setAllNonNull();
        auto val = overflowColValue.value;
        for (auto i = 0u; i < overflowColValue.numElements; i++) {
            vector.copyFromRowData(i, val);
            val += numBytesPerValue;
        }
    } else {
        auto overflowColNullData =
            overflowColValue.value + overflowColValue.numElements * numBytesPerValue;
        auto overflowColData = overflowColValue.value;
        for (auto i = 0u; i < overflowColValue.numElements; i++) {
            if (isOverflowColNull(overflowColNullData, i, colIdx)) {
                vector.setNull(i, true);
            } else {
                vector.setNull(i, false);
                vector.copyFromRowData(i, overflowColData);
            }
            overflowColData += numBytesPerValue;
        }
    }
    vector.state->getSelVectorUnsafe().setSelSize(overflowColValue.numElements);
}

void FactorizedTable::readUnflatCol(const uint8_t* tupleToRead, const SelectionVector& selVector,
    ft_col_idx_t colIdx, ValueVector& vector) const {
    auto vectorOverflowValue = *(overflow_value_t*)(tupleToRead + tableSchema.getColOffset(colIdx));
    KU_ASSERT(vector.state->getSelVector().isUnfiltered());
    if (hasNoNullGuarantee(colIdx)) {
        vector.setAllNonNull();
        auto val = vectorOverflowValue.value;
        for (auto i = 0u; i < vectorOverflowValue.numElements; i++) {
            auto pos = selVector[i];
            vector.copyFromRowData(i, val + (pos * vector.getNumBytesPerValue()));
        }
    } else {
        for (auto i = 0u; i < vectorOverflowValue.numElements; i++) {
            auto pos = selVector[i];
            if (isOverflowColNull(vectorOverflowValue.value + vectorOverflowValue.numElements *
                                                                  vector.getNumBytesPerValue(),
                    pos, colIdx)) {
                vector.setNull(i, true);
            } else {
                vector.setNull(i, false);
                vector.copyFromRowData(i,
                    vectorOverflowValue.value + pos * vector.getNumBytesPerValue());
            }
        }
    }
    vector.state->getSelVectorUnsafe().setSelSize(selVector.getSelSize());
}

void FactorizedTable::readFlatColToFlatVector(uint8_t* tupleToRead, ft_col_idx_t colIdx,
    ValueVector& vector, sel_t pos) const {
    if (isNonOverflowColNull(tupleToRead + tableSchema.getNullMapOffset(), colIdx)) {
        vector.setNull(pos, true);
    } else {
        vector.setNull(pos, false);
        vector.copyFromRowData(pos, tupleToRead + tableSchema.getColOffset(colIdx));
    }
}

void FactorizedTable::readFlatCol(uint8_t** tuplesToRead, ft_col_idx_t colIdx, ValueVector& vector,
    uint64_t numTuplesToRead) const {
    if (vector.state->isFlat()) {
        auto pos = vector.state->getSelVector()[0];
        readFlatColToFlatVector(tuplesToRead[0], colIdx, vector, pos);
    } else {
        readFlatColToUnflatVector(tuplesToRead, colIdx, vector, numTuplesToRead);
    }
}

void FactorizedTable::readFlatColToUnflatVector(uint8_t** tuplesToRead, ft_col_idx_t colIdx,
    ValueVector& vector, uint64_t numTuplesToRead) const {
    vector.state->getSelVectorUnsafe().setSelSize(numTuplesToRead);
    if (hasNoNullGuarantee(colIdx)) {
        vector.setAllNonNull();
        for (auto i = 0u; i < numTuplesToRead; i++) {
            auto positionInVectorToWrite = vector.state->getSelVector()[i];
            auto srcData = tuplesToRead[i] + tableSchema.getColOffset(colIdx);
            vector.copyFromRowData(positionInVectorToWrite, srcData);
        }
    } else {
        for (auto i = 0u; i < numTuplesToRead; i++) {
            auto positionInVectorToWrite = vector.state->getSelVector()[i];
            auto dataBuffer = tuplesToRead[i];
            if (isNonOverflowColNull(dataBuffer + tableSchema.getNullMapOffset(), colIdx)) {
                vector.setNull(positionInVectorToWrite, true);
            } else {
                vector.setNull(positionInVectorToWrite, false);
                vector.copyFromRowData(positionInVectorToWrite,
                    dataBuffer + tableSchema.getColOffset(colIdx));
            }
        }
    }
}

FactorizedTableIterator::FactorizedTableIterator(FactorizedTable& factorizedTable)
    : factorizedTable{factorizedTable}, currentTupleBuffer{nullptr}, numFlatTuples{0},
      nextFlatTupleIdx{0}, nextTupleIdx{1} {
    resetState();
}

void FactorizedTableIterator::getNext(FlatTuple& tuple) {
    // Go to the next tuple if we have iterated all the flat tuples of the current tuple.
    if (nextFlatTupleIdx >= numFlatTuples) {
        currentTupleBuffer = factorizedTable.getTuple(nextTupleIdx);
        numFlatTuples = factorizedTable.getNumFlatTuples(nextTupleIdx);
        nextFlatTupleIdx = 0;
        updateNumElementsInDataChunk();
        nextTupleIdx++;
    }
    for (auto i = 0ul; i < factorizedTable.getTableSchema()->getNumColumns(); i++) {
        auto column = factorizedTable.getTableSchema()->getColumn(i);
        if (column->isFlat()) {
            readFlatColToFlatTuple(i, currentTupleBuffer, tuple);
        } else {
            readUnflatColToFlatTuple(i, currentTupleBuffer, tuple);
        }
    }
    updateFlatTuplePositionsInDataChunk();
    nextFlatTupleIdx++;
}

void FactorizedTableIterator::resetState() {
    numFlatTuples = 0;
    nextFlatTupleIdx = 0;
    nextTupleIdx = 1;
    if (factorizedTable.getNumTuples()) {
        currentTupleBuffer = factorizedTable.getTuple(0);
        numFlatTuples = factorizedTable.getNumFlatTuples(0);
        updateNumElementsInDataChunk();
        updateInvalidEntriesInFlatTuplePositionsInDataChunk();
    }
}

void FactorizedTableIterator::readUnflatColToFlatTuple(ft_col_idx_t colIdx, uint8_t* valueBuffer,
    FlatTuple& tuple) {
    auto overflowValue =
        (overflow_value_t*)(valueBuffer + factorizedTable.getTableSchema()->getColOffset(colIdx));
    auto groupID = factorizedTable.getTableSchema()->getColumn(colIdx)->getGroupID();
    auto tupleSizeInOverflowBuffer =
        LogicalTypeUtils::getRowLayoutSize(tuple[colIdx].getDataType());
    valueBuffer = overflowValue->value +
                  tupleSizeInOverflowBuffer * flatTuplePositionsInDataChunk[groupID].first;
    auto isNull = factorizedTable.isOverflowColNull(
        overflowValue->value + tupleSizeInOverflowBuffer * overflowValue->numElements,
        flatTuplePositionsInDataChunk[groupID].first, colIdx);
    tuple[colIdx].setNull(isNull);
    if (!isNull) {
        tuple[colIdx].copyFromRowLayout(valueBuffer);
    }
}

void FactorizedTableIterator::readFlatColToFlatTuple(ft_col_idx_t colIdx, uint8_t* valueBuffer,
    FlatTuple& tuple) {
    auto isNull = factorizedTable.isNonOverflowColNull(
        valueBuffer + factorizedTable.getTableSchema()->getNullMapOffset(), colIdx);
    tuple[colIdx].setNull(isNull);
    if (!isNull) {
        tuple[colIdx].copyFromRowLayout(
            valueBuffer + factorizedTable.getTableSchema()->getColOffset(colIdx));
    }
}

void FactorizedTableIterator::updateInvalidEntriesInFlatTuplePositionsInDataChunk() {
    for (auto i = 0u; i < flatTuplePositionsInDataChunk.size(); i++) {
        bool isValidEntry = false;
        for (auto j = 0u; j < factorizedTable.getTableSchema()->getNumColumns(); j++) {
            if (factorizedTable.getTableSchema()->getColumn(j)->getGroupID() == i) {
                isValidEntry = true;
                break;
            }
        }
        if (!isValidEntry) {
            flatTuplePositionsInDataChunk[i] = std::make_pair(UINT64_MAX, UINT64_MAX);
        }
    }
}

void FactorizedTableIterator::updateNumElementsInDataChunk() {
    auto colOffsetInTupleBuffer = 0ul;
    for (auto i = 0u; i < factorizedTable.getTableSchema()->getNumColumns(); i++) {
        auto column = factorizedTable.getTableSchema()->getColumn(i);
        auto groupID = column->getGroupID();
        // If this is an unflat column, the number of elements is stored in the
        // overflow_value_t struct. Otherwise, the number of elements is 1.
        auto numElementsInDataChunk =
            column->isFlat() ?
                1 :
                ((overflow_value_t*)(currentTupleBuffer + colOffsetInTupleBuffer))->numElements;
        if (groupID >= flatTuplePositionsInDataChunk.size()) {
            flatTuplePositionsInDataChunk.resize(groupID + 1);
        }
        flatTuplePositionsInDataChunk[groupID] =
            std::make_pair(0 /* nextIdxToReadInDataChunk */, numElementsInDataChunk);
        colOffsetInTupleBuffer += column->getNumBytes();
    }
}

void FactorizedTableIterator::updateFlatTuplePositionsInDataChunk() {
    for (auto i = 0u; i < flatTuplePositionsInDataChunk.size(); i++) {
        if (!isValidDataChunkPos(i)) {
            continue;
        }
        flatTuplePositionsInDataChunk.at(i).first++;
        // If we have output all elements in the current column, we reset the
        // nextIdxToReadInDataChunk in the current column to 0.
        if (flatTuplePositionsInDataChunk.at(i).first >=
            flatTuplePositionsInDataChunk.at(i).second) {
            flatTuplePositionsInDataChunk.at(i).first = 0;
        } else {
            // If the current dataChunk is not full, then we don't need to update the next
            // dataChunk.
            break;
        }
    }
}

} // namespace processor
} // namespace lbug
