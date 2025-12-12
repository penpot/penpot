#include "processor/operator/order_by/order_by_key_encoder.h"

#include <cstdint>
#include <cstring>

#include "common/exception/runtime.h"
#include "common/string_format.h"
#include "common/utils.h"
#include "storage/storage_utils.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {
static constexpr uint64_t DATA_BLOCK_SIZE = common::TEMP_PAGE_SIZE;

OrderByKeyEncoder::OrderByKeyEncoder(const OrderByDataInfo& orderByDataInfo,
    MemoryManager* memoryManager, uint8_t ftIdx, uint32_t numTuplesPerBlockInFT,
    uint32_t numBytesPerTuple)
    : memoryManager{memoryManager}, isAscOrder{orderByDataInfo.isAscOrder},
      numBytesPerTuple{numBytesPerTuple}, ftIdx{ftIdx},
      numTuplesPerBlockInFT{numTuplesPerBlockInFT}, swapBytes{isLittleEndian()} {
    if (numTuplesPerBlockInFT > MAX_FT_BLOCK_OFFSET) {
        throw RuntimeException(
            "The number of tuples per block of factorizedTable exceeds the maximum blockOffset!");
    }
    keyBlocks.emplace_back(std::make_unique<DataBlock>(memoryManager, DATA_BLOCK_SIZE));
    KU_ASSERT(this->numBytesPerTuple == getNumBytesPerTuple());
    maxNumTuplesPerBlock = DATA_BLOCK_SIZE / numBytesPerTuple;
    if (maxNumTuplesPerBlock <= 0) {
        throw RuntimeException(
            stringFormat("TupleSize({} bytes) is larger than the LARGE_PAGE_SIZE({} bytes)",
                numBytesPerTuple, DATA_BLOCK_SIZE));
    }
    encodeFunctions.reserve(orderByDataInfo.keysPos.size());
    for (auto& type : orderByDataInfo.keyTypes) {
        encode_function_t encodeFunction;
        getEncodingFunction(type.getPhysicalType(), encodeFunction);
        encodeFunctions.push_back(std::move(encodeFunction));
    }
}

void OrderByKeyEncoder::encodeKeys(const std::vector<common::ValueVector*>& orderByKeys) {
    uint32_t numEntries = orderByKeys[0]->state->getSelVector().getSelSize();
    uint32_t encodedTuples = 0;
    while (numEntries > 0) {
        allocateMemoryIfFull();
        uint32_t numEntriesToEncode =
            std::min(numEntries, maxNumTuplesPerBlock - getNumTuplesInCurBlock());
        auto tuplePtr =
            keyBlocks.back()->getData() + keyBlocks.back()->numTuples * numBytesPerTuple;
        uint32_t tuplePtrOffset = 0;
        for (auto keyColIdx = 0u; keyColIdx < orderByKeys.size(); keyColIdx++) {
            encodeVector(orderByKeys[keyColIdx], tuplePtr + tuplePtrOffset, encodedTuples,
                numEntriesToEncode, keyColIdx);
            tuplePtrOffset += getEncodingSize(orderByKeys[keyColIdx]->dataType);
        }
        encodeFTIdx(numEntriesToEncode, tuplePtr + tuplePtrOffset);
        encodedTuples += numEntriesToEncode;
        keyBlocks.back()->numTuples += numEntriesToEncode;
        numEntries -= numEntriesToEncode;
    }
}

uint32_t OrderByKeyEncoder::getNumBytesPerTuple(const std::vector<ValueVector*>& keyVectors) {
    uint32_t result = 0u;
    for (auto& vector : keyVectors) {
        result += getEncodingSize(vector->dataType);
    }
    result += 8;
    return result;
}

uint32_t OrderByKeyEncoder::getEncodingSize(const LogicalType& dataType) {
    // Add one more byte for null flag.
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::STRING:
        // 1 byte for null flag + 1 byte to indicate long/short string + 12 bytes for string prefix
        return 2 + ku_string_t::SHORT_STR_LENGTH;
    default:
        return 1 + storage::StorageUtils::getDataTypeSize(dataType);
    }
}

void OrderByKeyEncoder::flipBytesIfNecessary(uint32_t keyColIdx, uint8_t* tuplePtr,
    uint32_t numEntriesToEncode, LogicalType& type) {
    if (!isAscOrder[keyColIdx]) {
        auto encodingSize = getEncodingSize(type);
        // If the current column is in desc order, flip all bytes.
        for (auto i = 0u; i < numEntriesToEncode; i++) {
            for (auto byte = 0u; byte < encodingSize; ++byte) {
                *(tuplePtr + byte) = ~*(tuplePtr + byte);
            }
            tuplePtr += numBytesPerTuple;
        }
    }
}

void OrderByKeyEncoder::encodeFlatVector(ValueVector* vector, uint8_t* tuplePtr,
    uint32_t keyColIdx) {
    auto pos = vector->state->getSelVector()[0];
    if (vector->isNull(pos)) {
        for (auto j = 0u; j < getEncodingSize(vector->dataType); j++) {
            *(tuplePtr + j) = UINT8_MAX;
        }
    } else {
        *tuplePtr = 0;
        encodeFunctions[keyColIdx](vector->getData() + pos * vector->getNumBytesPerValue(),
            tuplePtr + 1, swapBytes);
    }
}

void OrderByKeyEncoder::encodeUnflatVector(ValueVector* vector, uint8_t* tuplePtr,
    uint32_t encodedTuples, uint32_t numEntriesToEncode, uint32_t keyColIdx) {
    if (vector->state->getSelVector().isUnfiltered()) {
        auto value = vector->getData() + encodedTuples * vector->getNumBytesPerValue();
        if (vector->hasNoNullsGuarantee()) {
            for (auto i = 0u; i < numEntriesToEncode; i++) {
                *tuplePtr = 0;
                encodeFunctions[keyColIdx](value, tuplePtr + 1, swapBytes);
                tuplePtr += numBytesPerTuple;
                value += vector->getNumBytesPerValue();
            }
        } else {
            for (auto i = 0u; i < numEntriesToEncode; i++) {
                if (vector->isNull(encodedTuples + i)) {
                    for (auto j = 0u; j < getEncodingSize(vector->dataType); j++) {
                        *(tuplePtr + j) = UINT8_MAX;
                    }
                } else {
                    *tuplePtr = 0;
                    encodeFunctions[keyColIdx](value, tuplePtr + 1, swapBytes);
                }
                tuplePtr += numBytesPerTuple;
                value += vector->getNumBytesPerValue();
            }
        }
    } else {
        if (vector->hasNoNullsGuarantee()) {
            for (auto i = 0u; i < numEntriesToEncode; i++) {
                *tuplePtr = 0;
                encodeFunctions[keyColIdx](vector->getData() +
                                               vector->state->getSelVector()[i + encodedTuples] *
                                                   vector->getNumBytesPerValue(),
                    tuplePtr + 1, swapBytes);
                tuplePtr += numBytesPerTuple;
            }
        } else {
            for (auto i = 0u; i < numEntriesToEncode; i++) {
                auto pos = vector->state->getSelVector()[i + encodedTuples];
                if (vector->isNull(pos)) {
                    for (auto j = 0u; j < getEncodingSize(vector->dataType); j++) {
                        *(tuplePtr + j) = UINT8_MAX;
                    }
                } else {
                    *tuplePtr = 0;
                    encodeFunctions[keyColIdx](vector->getData() +
                                                   pos * vector->getNumBytesPerValue(),
                        tuplePtr + 1, swapBytes);
                }
                tuplePtr += numBytesPerTuple;
            }
        }
    }
}

void OrderByKeyEncoder::encodeVector(ValueVector* vector, uint8_t* tuplePtr, uint32_t encodedTuples,
    uint32_t numEntriesToEncode, uint32_t keyColIdx) {
    if (vector->state->isFlat()) {
        encodeFlatVector(vector, tuplePtr, keyColIdx);
    } else {
        encodeUnflatVector(vector, tuplePtr, encodedTuples, numEntriesToEncode, keyColIdx);
    }
    flipBytesIfNecessary(keyColIdx, tuplePtr, numEntriesToEncode, vector->dataType);
}

void OrderByKeyEncoder::encodeFTIdx(uint32_t numEntriesToEncode, uint8_t* tupleInfoPtr) {
    uint32_t numUpdatedFTInfoEntries = 0;
    while (numUpdatedFTInfoEntries < numEntriesToEncode) {
        auto nextBatchOfEntries = std::min(numEntriesToEncode - numUpdatedFTInfoEntries,
            numTuplesPerBlockInFT - ftBlockOffset);
        for (auto i = 0u; i < nextBatchOfEntries; i++) {
            *(uint32_t*)tupleInfoPtr = ftBlockIdx;
            *(uint32_t*)(tupleInfoPtr + 4) = ftBlockOffset;
            *(uint8_t*)(tupleInfoPtr + 7) = ftIdx;
            tupleInfoPtr += numBytesPerTuple;
            ftBlockOffset++;
        }
        numUpdatedFTInfoEntries += nextBatchOfEntries;
        if (ftBlockOffset == numTuplesPerBlockInFT) {
            ftBlockIdx++;
            ftBlockOffset = 0;
        }
    }
}

void OrderByKeyEncoder::allocateMemoryIfFull() {
    if (getNumTuplesInCurBlock() == maxNumTuplesPerBlock) {
        keyBlocks.emplace_back(std::make_shared<DataBlock>(memoryManager, DATA_BLOCK_SIZE));
    }
}

void OrderByKeyEncoder::getEncodingFunction(PhysicalTypeID physicalType, encode_function_t& func) {
    switch (physicalType) {
    case PhysicalTypeID::BOOL: {
        func = encodeTemplate<bool>;
        return;
    }
    case PhysicalTypeID::INT64: {
        func = encodeTemplate<int64_t>;
        return;
    }
    case PhysicalTypeID::INT32: {
        func = encodeTemplate<int32_t>;
        return;
    }
    case PhysicalTypeID::INT16: {
        func = encodeTemplate<int16_t>;
        return;
    }
    case PhysicalTypeID::INT8: {
        func = encodeTemplate<int8_t>;
        return;
    }
    case PhysicalTypeID::UINT64: {
        func = encodeTemplate<uint64_t>;
        return;
    }
    case PhysicalTypeID::UINT32: {
        func = encodeTemplate<uint32_t>;
        return;
    }
    case PhysicalTypeID::UINT16: {
        func = encodeTemplate<uint16_t>;
        return;
    }
    case PhysicalTypeID::UINT8: {
        func = encodeTemplate<uint8_t>;
        return;
    }
    case PhysicalTypeID::INT128: {
        func = encodeTemplate<int128_t>;
        return;
    }
    case PhysicalTypeID::DOUBLE: {
        func = encodeTemplate<double>;
        return;
    }
    case PhysicalTypeID::FLOAT: {
        func = encodeTemplate<float>;
        return;
    }
    case PhysicalTypeID::STRING: {
        func = encodeTemplate<ku_string_t>;
        return;
    }
    case PhysicalTypeID::INTERVAL: {
        func = encodeTemplate<interval_t>;
        return;
    }
    case PhysicalTypeID::UINT128: {
        func = encodeTemplate<uint128_t>;
        return;
    }
    default:
        KU_UNREACHABLE;
    }
}

template<>
void OrderByKeyEncoder::encodeData(int8_t data, uint8_t* resultPtr, bool /*swapBytes*/) {
    memcpy(resultPtr, (void*)&data, sizeof(data));
    resultPtr[0] = flipSign(resultPtr[0]);
}

template<>
void OrderByKeyEncoder::encodeData(int16_t data, uint8_t* resultPtr, bool swapBytes) {
    if (swapBytes) {
        data = BSWAP16(data);
    }
    memcpy(resultPtr, (void*)&data, sizeof(data));
    resultPtr[0] = flipSign(resultPtr[0]);
}

template<>
void OrderByKeyEncoder::encodeData(int32_t data, uint8_t* resultPtr, bool swapBytes) {
    if (swapBytes) {
        data = BSWAP32(data);
    }
    memcpy(resultPtr, (void*)&data, sizeof(data));
    resultPtr[0] = flipSign(resultPtr[0]);
}

template<>
void OrderByKeyEncoder::encodeData(int64_t data, uint8_t* resultPtr, bool swapBytes) {
    if (swapBytes) {
        data = BSWAP64(data);
    }
    memcpy(resultPtr, (void*)&data, sizeof(data));
    resultPtr[0] = flipSign(resultPtr[0]);
}

template<>
void OrderByKeyEncoder::encodeData(uint8_t data, uint8_t* resultPtr, bool /*swapBytes*/) {
    memcpy(resultPtr, (void*)&data, sizeof(data));
}

template<>
void OrderByKeyEncoder::encodeData(uint16_t data, uint8_t* resultPtr, bool swapBytes) {
    if (swapBytes) {
        data = BSWAP16(data);
    }
    memcpy(resultPtr, (void*)&data, sizeof(data));
}

template<>
void OrderByKeyEncoder::encodeData(uint32_t data, uint8_t* resultPtr, bool swapBytes) {
    if (swapBytes) {
        data = BSWAP32(data);
    }
    memcpy(resultPtr, (void*)&data, sizeof(data));
}

template<>
void OrderByKeyEncoder::encodeData(uint64_t data, uint8_t* resultPtr, bool swapBytes) {
    if (swapBytes) {
        data = BSWAP64(data);
    }
    memcpy(resultPtr, (void*)&data, sizeof(data));
}

template<>
void OrderByKeyEncoder::encodeData(common::int128_t data, uint8_t* resultPtr, bool swapBytes) {
    encodeData<int64_t>(data.high, resultPtr, swapBytes);
    encodeData<uint64_t>(data.low, resultPtr + sizeof(data.high), swapBytes);
}

template<>
void OrderByKeyEncoder::encodeData(common::uint128_t data, uint8_t* resultPtr, bool swapBytes) {
    encodeData<uint64_t>(data.high, resultPtr, swapBytes);
    encodeData<uint64_t>(data.low, resultPtr + sizeof(data.high), swapBytes);
}

template<>
void OrderByKeyEncoder::encodeData(bool data, uint8_t* resultPtr, bool /*swapBytes*/) {
    uint8_t val = data ? 1 : 0;
    memcpy(resultPtr, (void*)&val, sizeof(data));
}

template<>
void OrderByKeyEncoder::encodeData(double data, uint8_t* resultPtr, bool swapBytes) {
    memcpy(resultPtr, &data, sizeof(data));
    uint64_t* dataBytes = (uint64_t*)resultPtr;
    if (swapBytes) {
        *dataBytes = BSWAP64(*dataBytes);
    }
    if (data < (double)0) {
        *dataBytes = ~*dataBytes;
    } else {
        resultPtr[0] = flipSign(resultPtr[0]);
    }
}

template<>
void OrderByKeyEncoder::encodeData(date_t data, uint8_t* resultPtr, bool swapBytes) {
    encodeData(data.days, resultPtr, swapBytes);
}

template<>
void OrderByKeyEncoder::encodeData(timestamp_t data, uint8_t* resultPtr, bool swapBytes) {
    encodeData(data.value, resultPtr, swapBytes);
}

template<>
void OrderByKeyEncoder::encodeData(interval_t data, uint8_t* resultPtr, bool swapBytes) {
    int64_t months = 0, days = 0, micros = 0;
    Interval::normalizeIntervalEntries(data, months, days, micros);
    encodeData((int32_t)months, resultPtr, swapBytes);
    resultPtr += sizeof(data.months);
    encodeData((int32_t)days, resultPtr, swapBytes);
    resultPtr += sizeof(data.days);
    encodeData(micros, resultPtr, swapBytes);
}

template<>
void OrderByKeyEncoder::encodeData(ku_string_t data, uint8_t* resultPtr, bool /*swapBytes*/) {
    // Only encode the prefix of ku_string.
    memcpy(resultPtr, (void*)data.getAsString().c_str(),
        std::min((uint32_t)ku_string_t::SHORT_STR_LENGTH, data.len));
    if (ku_string_t::isShortString(data.len)) {
        memset(resultPtr + data.len, '\0', ku_string_t::SHORT_STR_LENGTH + 1 - data.len);
    } else {
        resultPtr[12] = UINT8_MAX;
    }
}

template<>
void OrderByKeyEncoder::encodeData(float data, uint8_t* resultPtr, bool swapBytes) {
    memcpy(resultPtr, &data, sizeof(data));
    uint32_t* dataBytes = (uint32_t*)resultPtr;
    if (swapBytes) {
        *dataBytes = BSWAP32(*dataBytes);
    }
    if (data < (float)0) {
        *dataBytes = ~*dataBytes;
    } else {
        resultPtr[0] = flipSign(resultPtr[0]);
    }
}

} // namespace processor
} // namespace lbug
