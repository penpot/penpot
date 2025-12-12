#pragma once

#include <functional>
#include <vector>

#include "common/vector/value_vector.h"
#include "order_by_data_info.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace processor {

#define BSWAP64(x)                                                                                 \
    ((uint64_t)((((uint64_t)(x) & 0xff00000000000000ull) >> 56) |                                  \
                (((uint64_t)(x) & 0x00ff000000000000ull) >> 40) |                                  \
                (((uint64_t)(x) & 0x0000ff0000000000ull) >> 24) |                                  \
                (((uint64_t)(x) & 0x000000ff00000000ull) >> 8) |                                   \
                (((uint64_t)(x) & 0x00000000ff000000ull) << 8) |                                   \
                (((uint64_t)(x) & 0x0000000000ff0000ull) << 24) |                                  \
                (((uint64_t)(x) & 0x000000000000ff00ull) << 40) |                                  \
                (((uint64_t)(x) & 0x00000000000000ffull) << 56)))

#define BSWAP32(x)                                                                                 \
    ((uint32_t)((((uint32_t)(x) & 0xff000000) >> 24) | (((uint32_t)(x) & 0x00ff0000) >> 8) |       \
                (((uint32_t)(x) & 0x0000ff00) << 8) | (((uint32_t)(x) & 0x000000ff) << 24)))

#define BSWAP16(x) ((uint16_t)((((uint16_t)(x) & 0xff00) >> 8) | (((uint16_t)(x) & 0x00ff) << 8)))

// The OrderByKeyEncoder encodes all columns in the ORDER BY clause into a single binary sequence
// that, when compared using memcmp will yield the correct overall sorting order. On little-endian
// hardware, the least-significant byte is stored at the smallest address. To encode the sorting
// order, we need the big-endian representation for values. For example: we want to encode 73(INT64)
// and 38(INT64) as an 8-byte binary string The encoding in little-endian hardware is:
// 73=0x4900000000000000 38=0x2600000000000000, which doesn't preserve the order. The encoding in
// big-endian hardware is: 73=0x0000000000000049 38=0x0000000000000026, which can easily be compared
// using memcmp. In addition, The first bit is also flipped to preserve ordering between positive
// and negative numbers. So the final encoding for 73(INT64) and 38(INT64) as an 8-byte binary
// string is: 73=0x8000000000000049 38=0x8000000000000026. To handle the null in comparison, we
// add an extra byte(called the NULL flag) to represent whether this value is null or not.

using encode_function_t = std::function<void(const uint8_t*, uint8_t*, bool)>;

class OrderByKeyEncoder {
public:
    OrderByKeyEncoder(const OrderByDataInfo& orderByDataInfo, storage::MemoryManager* memoryManager,
        uint8_t ftIdx, uint32_t numTuplesPerBlockInFT, uint32_t numBytesPerTuple);

    inline std::vector<std::shared_ptr<DataBlock>>& getKeyBlocks() { return keyBlocks; }

    inline uint32_t getNumBytesPerTuple() const { return numBytesPerTuple; }

    inline uint32_t getNumTuplesInCurBlock() const { return keyBlocks.back()->numTuples; }

    static uint32_t getNumBytesPerTuple(const std::vector<common::ValueVector*>& keyVectors);

    static inline uint32_t getEncodedFTBlockIdx(const uint8_t* tupleInfoPtr) {
        return *(uint32_t*)tupleInfoPtr;
    }

    // Note: We only encode 3 bytes for ftBlockOffset, but we are reading 4 bytes from tupleInfoPtr.
    // We need to do a bit mask to set the most significant byte to 0x00.
    static inline uint32_t getEncodedFTBlockOffset(const uint8_t* tupleInfoPtr) {
        return (*(uint32_t*)(tupleInfoPtr + 4) & 0x00FFFFFF);
    }

    static inline uint8_t getEncodedFTIdx(const uint8_t* tupleInfoPtr) {
        return *(tupleInfoPtr + 7);
    }

    static inline bool isNullVal(const uint8_t* nullBytePtr, bool isAscOrder) {
        return *(nullBytePtr) == (isAscOrder ? UINT8_MAX : 0);
    }

    static inline bool isLongStr(const uint8_t* strBuffer, bool isAsc) {
        return *(strBuffer + 13) == (isAsc ? UINT8_MAX : 0);
    }

    static uint32_t getEncodingSize(const common::LogicalType& dataType);

    void encodeKeys(const std::vector<common::ValueVector*>& orderByKeys);

    inline void clear() { keyBlocks.clear(); }

private:
    template<typename type>
    static inline void encodeTemplate(const uint8_t* data, uint8_t* resultPtr, bool swapBytes) {
        OrderByKeyEncoder::encodeData(*(type*)data, resultPtr, swapBytes);
    }

    template<typename type>
    static void encodeData(type /*data*/, uint8_t* /*resultPtr*/, bool /*swapBytes*/) {
        KU_UNREACHABLE;
    }

    static inline uint8_t flipSign(uint8_t key_byte) { return key_byte ^ 128; }

    void flipBytesIfNecessary(uint32_t keyColIdx, uint8_t* tuplePtr, uint32_t numEntriesToEncode,
        common::LogicalType& type);

    void encodeFlatVector(common::ValueVector* vector, uint8_t* tuplePtr, uint32_t keyColIdx);

    void encodeUnflatVector(common ::ValueVector* vector, uint8_t* tuplePtr, uint32_t encodedTuples,
        uint32_t numEntriesToEncode, uint32_t keyColIdx);

    void encodeVector(common::ValueVector* vector, uint8_t* tuplePtr, uint32_t encodedTuples,
        uint32_t numEntriesToEncode, uint32_t keyColIdx);

    void encodeFTIdx(uint32_t numEntriesToEncode, uint8_t* tupleInfoPtr);

    void allocateMemoryIfFull();

    static void getEncodingFunction(common::PhysicalTypeID physicalType, encode_function_t& func);

private:
    storage::MemoryManager* memoryManager;
    std::vector<std::shared_ptr<DataBlock>> keyBlocks;
    std::vector<bool> isAscOrder;
    uint32_t numBytesPerTuple;
    uint32_t maxNumTuplesPerBlock;
    uint32_t ftBlockIdx = 0;
    // Since we encode 3 bytes for ftBlockOffset, the maxFTBlockOffset is 2^24 - 1.
    static const uint32_t MAX_FT_BLOCK_OFFSET = (1ul << 24) - 1;
    uint32_t ftBlockOffset = 0;
    // We only encode 1 byte for ftIndex, this limits the maximum number of threads of our system to
    // 256.
    uint8_t ftIdx;
    uint32_t numTuplesPerBlockInFT;
    // We need to swap the encoded binary strings if we are using little endian hardware.
    bool swapBytes;
    std::vector<encode_function_t> encodeFunctions;
};

} // namespace processor
} // namespace lbug
