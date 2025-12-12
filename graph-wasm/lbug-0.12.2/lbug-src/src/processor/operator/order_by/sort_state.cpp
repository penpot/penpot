#include "processor/operator/order_by/sort_state.h"

#include <mutex>

#include "common/constants.h"
#include "common/system_config.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void SortSharedState::init(const OrderByDataInfo& orderByDataInfo) {
    auto encodedKeyBlockColOffset = 0ul;
    for (auto i = 0u; i < orderByDataInfo.keysPos.size(); ++i) {
        const auto& dataType = orderByDataInfo.keyTypes[i];
        if (PhysicalTypeID::STRING == dataType.getPhysicalType()) {
            // If this is a string column, we need to find the factorizedTable offset for this
            // column.
            auto ftColIdx = orderByDataInfo.keyInPayloadPos[i];
            strKeyColsInfo.emplace_back(orderByDataInfo.payloadTableSchema.getColOffset(ftColIdx),
                encodedKeyBlockColOffset, orderByDataInfo.isAscOrder[i]);
        }
        encodedKeyBlockColOffset += OrderByKeyEncoder::getEncodingSize(dataType);
    }
    numBytesPerTuple = encodedKeyBlockColOffset + OrderByConstants::NUM_BYTES_FOR_PAYLOAD_IDX;
}

std::pair<uint64_t, FactorizedTable*> SortSharedState::getLocalPayloadTable(
    storage::MemoryManager& memoryManager, const FactorizedTableSchema& payloadTableSchema) {
    std::unique_lock lck{mtx};
    auto payloadTable =
        std::make_unique<FactorizedTable>(&memoryManager, payloadTableSchema.copy());
    auto result = std::make_pair(nextTableIdx++, payloadTable.get());
    payloadTables.push_back(std::move(payloadTable));
    return result;
}

void SortSharedState::appendLocalSortedKeyBlock(
    const std::shared_ptr<MergedKeyBlocks>& mergedDataBlocks) {
    std::unique_lock lck{mtx};
    sortedKeyBlocks->emplace(mergedDataBlocks);
}

void SortSharedState::combineFTHasNoNullGuarantee() {
    for (auto i = 1u; i < payloadTables.size(); i++) {
        payloadTables[0]->mergeMayContainNulls(*payloadTables[i]);
    }
}

std::vector<FactorizedTable*> SortSharedState::getPayloadTables() const {
    std::vector<FactorizedTable*> payloadTablesToReturn;
    payloadTablesToReturn.reserve(payloadTables.size());
    for (auto& payloadTable : payloadTables) {
        payloadTablesToReturn.push_back(payloadTable.get());
    }
    return payloadTablesToReturn;
}

void SortLocalState::init(const OrderByDataInfo& orderByDataInfo, SortSharedState& sharedState,
    storage::MemoryManager* memoryManager) {
    auto [idx, table] =
        sharedState.getLocalPayloadTable(*memoryManager, orderByDataInfo.payloadTableSchema);
    globalIdx = idx;
    payloadTable = table;
    orderByKeyEncoder = std::make_unique<OrderByKeyEncoder>(orderByDataInfo, memoryManager,
        globalIdx, payloadTable->getNumTuplesPerBlock(), sharedState.getNumBytesPerTuple());
    radixSorter = std::make_unique<RadixSort>(memoryManager, *payloadTable, *orderByKeyEncoder,
        sharedState.getStrKeyColInfo());
}

void SortLocalState::append(const std::vector<common::ValueVector*>& keyVectors,
    const std::vector<common::ValueVector*>& payloadVectors) {
    orderByKeyEncoder->encodeKeys(keyVectors);
    payloadTable->append(payloadVectors);
}

void SortLocalState::finalize(lbug::processor::SortSharedState& sharedState) {
    for (auto& keyBlock : orderByKeyEncoder->getKeyBlocks()) {
        if (keyBlock->numTuples > 0) {
            radixSorter->sortSingleKeyBlock(*keyBlock);
            sharedState.appendLocalSortedKeyBlock(
                make_shared<MergedKeyBlocks>(orderByKeyEncoder->getNumBytesPerTuple(), keyBlock));
        }
    }
    orderByKeyEncoder->clear();
}

PayloadScanner::PayloadScanner(MergedKeyBlocks* keyBlockToScan,
    std::vector<FactorizedTable*> payloadTables, uint64_t skipNumber, uint64_t limitNumber)
    : keyBlockToScan{keyBlockToScan}, payloadTables{std::move(payloadTables)},
      limitNumber{limitNumber} {
    if (this->keyBlockToScan == nullptr || this->keyBlockToScan->getNumTuples() == 0) {
        nextTupleIdxToReadInMergedKeyBlock = 0;
        endTuplesIdxToReadInMergedKeyBlock = 0;
        return;
    }
    payloadIdxOffset =
        this->keyBlockToScan->getNumBytesPerTuple() - OrderByConstants::NUM_BYTES_FOR_PAYLOAD_IDX;
    colsToScan = std::vector<uint32_t>(this->payloadTables[0]->getTableSchema()->getNumColumns());
    iota(colsToScan.begin(), colsToScan.end(), 0);
    hasUnflatColInPayload = this->payloadTables[0]->hasUnflatCol();
    if (!hasUnflatColInPayload) {
        tuplesToRead = std::make_unique<uint8_t*[]>(DEFAULT_VECTOR_CAPACITY);
    }
    nextTupleIdxToReadInMergedKeyBlock = skipNumber == UINT64_MAX ? 0 : skipNumber;
    endTuplesIdxToReadInMergedKeyBlock =
        limitNumber == UINT64_MAX ? this->keyBlockToScan->getNumTuples() :
                                    std::min(nextTupleIdxToReadInMergedKeyBlock + limitNumber,
                                        this->keyBlockToScan->getNumTuples());
    blockPtrInfo = std::make_unique<BlockPtrInfo>(nextTupleIdxToReadInMergedKeyBlock,
        endTuplesIdxToReadInMergedKeyBlock, this->keyBlockToScan);
}

uint64_t PayloadScanner::scan(std::vector<common::ValueVector*> vectorsToRead) {
    if (limitNumber <= 0 ||
        nextTupleIdxToReadInMergedKeyBlock >= endTuplesIdxToReadInMergedKeyBlock) {
        return 0;
    }
    if (scanSingleTuple(vectorsToRead)) {
        auto payloadInfo = blockPtrInfo->curTuplePtr + payloadIdxOffset;
        auto blockIdx = OrderByKeyEncoder::getEncodedFTBlockIdx(payloadInfo);
        auto blockOffset = OrderByKeyEncoder::getEncodedFTBlockOffset(payloadInfo);
        auto payloadTable = payloadTables[OrderByKeyEncoder::getEncodedFTIdx(payloadInfo)];
        payloadTable->scan(vectorsToRead,
            blockIdx * payloadTable->getNumTuplesPerBlock() + blockOffset, 1 /* numTuples */);
        blockPtrInfo->curTuplePtr += keyBlockToScan->getNumBytesPerTuple();
        blockPtrInfo->updateTuplePtrIfNecessary();
        nextTupleIdxToReadInMergedKeyBlock++;
        applyLimitOnResultVectors(vectorsToRead);
        return 1;
    } else {
        auto numTuplesToRead = std::min(DEFAULT_VECTOR_CAPACITY,
            endTuplesIdxToReadInMergedKeyBlock - nextTupleIdxToReadInMergedKeyBlock);
        auto numTuplesRead = 0u;
        while (numTuplesRead < numTuplesToRead) {
            auto numTuplesToReadInCurBlock = std::min(numTuplesToRead - numTuplesRead,
                blockPtrInfo->getNumTuplesLeftInCurBlock());
            for (auto i = 0u; i < numTuplesToReadInCurBlock; i++) {
                auto payloadInfo = blockPtrInfo->curTuplePtr + payloadIdxOffset;
                auto blockIdx = OrderByKeyEncoder::getEncodedFTBlockIdx(payloadInfo);
                auto blockOffset = OrderByKeyEncoder::getEncodedFTBlockOffset(payloadInfo);
                auto ft = payloadTables[OrderByKeyEncoder::getEncodedFTIdx(payloadInfo)];
                tuplesToRead[numTuplesRead + i] =
                    ft->getTuple(blockIdx * ft->getNumTuplesPerBlock() + blockOffset);
                blockPtrInfo->curTuplePtr += keyBlockToScan->getNumBytesPerTuple();
            }
            blockPtrInfo->updateTuplePtrIfNecessary();
            numTuplesRead += numTuplesToReadInCurBlock;
        }
        // TODO(Ziyi): This is a hacky way of using factorizedTable::lookup function,
        // since the tuples in tuplesToRead may not belong to factorizedTable0. The
        // lookup function doesn't perform a check on whether it holds all the tuples in
        // tuplesToRead. We should optimize this lookup function in the orderByScan
        // optimization PR.
        payloadTables[0]->lookup(vectorsToRead, colsToScan, tuplesToRead.get(), 0, numTuplesToRead);
        nextTupleIdxToReadInMergedKeyBlock += numTuplesToRead;
        return numTuplesRead;
    }
}

bool PayloadScanner::scanSingleTuple(std::vector<common::ValueVector*> vectorsToRead) const {
    // If there is an unflat col in factorizedTable or flat vector in vectorsToRead, we can only
    // read one tuple at a time. Otherwise, we can read min(DEFAULT_VECTOR_CAPACITY,
    // numTuplesRemainingInMemBlock) tuples.
    bool hasFlatVectorToRead = false;
    for (auto& vector : vectorsToRead) {
        if (vector->state->isFlat()) {
            hasFlatVectorToRead = true;
        }
    }
    return hasUnflatColInPayload || hasFlatVectorToRead;
}

void PayloadScanner::applyLimitOnResultVectors(std::vector<common::ValueVector*> vectorsToRead) {
    // The query doesn't contain a limit clause.
    if (limitNumber == UINT64_MAX) {
        return;
    }
    // Otherwise, we have to figure out the number of tuples in current batch exceeds the limit
    // number.
    common::ValueVector* unflatVector = nullptr;
    for (auto& vector : vectorsToRead) {
        if (!vector->state->isFlat()) {
            unflatVector = vector;
        }
    }
    if (unflatVector != nullptr) {
        unflatVector->state->getSelVectorUnsafe().setSelSize(
            std::min(limitNumber, (uint64_t)unflatVector->state->getSelVector().getSelSize()));
        limitNumber -= unflatVector->state->getSelVector().getSelSize();
    } else {
        limitNumber--;
    }
}

} // namespace processor
} // namespace lbug
