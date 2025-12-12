#pragma once

#include <array>
#include <vector>

#include "common/arrow/arrow.h"
#include "common/arrow/arrow_buffer.h"
#include "common/types/types.h"

struct ArrowSchema;

namespace lbug {
namespace processor {
class FlatTuple;
}

namespace common {
class Value;

// An Arrow Vector(i.e., Array) is defined by a few pieces of metadata and data:
//  1) a logical data type;
//  2) a sequence of buffers: validity bitmaps, data buffer, overflow(optional), children(optional).
//  3) a length as a 64-bit signed integer;
//  4) a null count as a 64-bit signed integer;
//  5) an optional dictionary for dictionary-encoded arrays.
// See https://arrow.apache.org/docs/format/Columnar.html for more details.

static inline uint64_t getNumBytesForBits(uint64_t numBits) {
    return (numBits + 7) / 8;
}

struct ArrowVector {
    ArrowBuffer data;
    ArrowBuffer validity;
    ArrowBuffer overflow;

    int64_t numValues = 0;
    int64_t capacity = 0;
    int64_t numNulls = 0;

    std::vector<std::unique_ptr<ArrowVector>> childData;

    // The arrow array C API data, only set after Finalize
    std::unique_ptr<ArrowArray> array;
    std::array<const void*, 3> buffers = {{nullptr, nullptr, nullptr}};
    std::vector<ArrowArray*> childPointers;
};

// An arrow data chunk consisting of N rows in columnar format.
class ArrowRowBatch {
public:
    ArrowRowBatch(const std::vector<LogicalType>& types, std::int64_t capacity,
        bool fallbackExtensionTypes);

    void append(const processor::FlatTuple& tuple);
    std::int64_t size() const { return numTuples; }
    ArrowArray toArray(const std::vector<LogicalType>& types);

private:
    static void appendValue(ArrowVector* vector, const Value& value, bool fallbackExtensionTypes);

    static ArrowArray* convertVectorToArray(ArrowVector& vector, const LogicalType& type,
        bool fallbackExtensionTypes);
    static ArrowArray* convertStructVectorToArray(ArrowVector& vector, const LogicalType& type,
        bool fallbackExtensionTypes);
    static ArrowArray* convertInternalIDVectorToArray(ArrowVector& vector, const LogicalType& type,
        bool fallbackExtensionTypes);

    static void copyNonNullValue(ArrowVector* vector, const Value& value, std::int64_t pos,
        bool fallbackExtensionTypes);
    static void copyNullValue(ArrowVector* vector, const Value& value, std::int64_t pos);

    template<LogicalTypeID DT>
    static void templateCopyNonNullValue(ArrowVector* vector, const Value& value, std::int64_t pos,
        bool fallbackExtensionTypes);
    template<LogicalTypeID DT>
    static void templateCopyNullValue(ArrowVector* vector, std::int64_t pos);
    static void copyNullValueUnion(ArrowVector* vector, const Value& value, std::int64_t pos);
    template<LogicalTypeID DT>
    static ArrowArray* templateCreateArray(ArrowVector& vector, const LogicalType& type,
        bool fallbackExtensionTypes);

private:
    std::vector<std::unique_ptr<ArrowVector>> vectors;
    std::int64_t numTuples;
    bool fallbackExtensionTypes = false;
};

} // namespace common
} // namespace lbug
