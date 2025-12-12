#pragma once

#include <cstdint>
#include <cstring>

namespace lbug {
namespace common {

class NullBuffer {

public:
    constexpr static const uint64_t NUM_NULL_MASKS_PER_BYTE = 8;

    static inline bool isNull(const uint8_t* nullBytes, uint64_t valueIdx) {
        return nullBytes[valueIdx / NUM_NULL_MASKS_PER_BYTE] &
               (1 << (valueIdx % NUM_NULL_MASKS_PER_BYTE));
    }

    static inline void setNull(uint8_t* nullBytes, uint64_t valueIdx) {
        nullBytes[valueIdx / NUM_NULL_MASKS_PER_BYTE] |=
            (1 << (valueIdx % NUM_NULL_MASKS_PER_BYTE));
    }

    static inline void setNoNull(uint8_t* nullBytes, uint64_t valueIdx) {
        nullBytes[valueIdx / NUM_NULL_MASKS_PER_BYTE] &=
            ~(1 << (valueIdx % NUM_NULL_MASKS_PER_BYTE));
    }

    static inline uint64_t getNumBytesForNullValues(uint64_t numValues) {
        return (numValues + NUM_NULL_MASKS_PER_BYTE - 1) / NUM_NULL_MASKS_PER_BYTE;
    }

    static inline void initNullBytes(uint8_t* nullBytes, uint64_t numValues) {
        memset(nullBytes, 0 /* value */, getNumBytesForNullValues(numValues));
    }
};

} // namespace common
} // namespace lbug
