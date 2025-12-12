#include "common/arrow/arrow_converter.h"
#include "common/exception/runtime.h"
#include "common/types/int128_t.h"
#include "common/types/interval_t.h"
#include "common/types/types.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {

// scans are based on data specification found here
// https://arrow.apache.org/docs/format/Columnar.html

// all offsets are measured by value, not physical size

template<typename T>
static void scanArrowArrayFixedSizePrimitive(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto arrayBuffer = (const uint8_t*)array->buffers[1];
    mask->copyToValueVector(&outputVector, dstOffset, count);
    memcpy(outputVector.getData() + dstOffset * outputVector.getNumBytesPerValue(),
        arrayBuffer + srcOffset * sizeof(T), count * sizeof(T));
}

template<typename SRC, typename DST>
static void scanArrowArrayFixedSizePrimitiveAndCastTo(const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto arrayBuffer = (const SRC*)array->buffers[1];
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curValue = arrayBuffer[i + srcOffset];
            outputVector.setValue<DST>(i + dstOffset, (DST)curValue);
        }
    }
}

template<>
void scanArrowArrayFixedSizePrimitive<bool>(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto arrayBuffer = (const uint8_t*)array->buffers[1];
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        outputVector.setValue<bool>(i + dstOffset,
            NullMask::isNull((const uint64_t*)arrayBuffer, i + srcOffset));
    }
}

static void scanArrowArrayDurationScaledUp(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, int64_t scaleFactor, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto arrayBuffer = ((const int64_t*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curValue = arrayBuffer[i];
            outputVector.setValue<interval_t>(i + dstOffset,
                interval_t(0, 0, curValue * scaleFactor));
        }
    }
}

static void scanArrowArrayDurationScaledDown(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, int64_t scaleFactor, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto arrayBuffer = ((const int64_t*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curValue = arrayBuffer[i];
            outputVector.setValue<interval_t>(i + dstOffset,
                interval_t(0, 0, curValue / scaleFactor));
        }
    }
}

static void scanArrowArrayMonthInterval(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto arrayBuffer = ((const int32_t*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curValue = arrayBuffer[i];
            outputVector.setValue<interval_t>(i + dstOffset, interval_t(curValue, 0, 0));
        }
    }
}

static void scanArrowArrayDayTimeInterval(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto arrayBuffer = ((const int64_t*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            int64_t curValue = arrayBuffer[i];
            int32_t day = curValue;
            int64_t micros = (curValue >> (4 * sizeof(int64_t))) * 1000;
            // arrow stores ms, while we store us
            outputVector.setValue<interval_t>(i + dstOffset, interval_t(0, day, micros));
        }
    }
}

static void scanArrowArrayMonthDayNanoInterval(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto arrayBuffer =
        (const int64_t*)((const uint8_t*)array->buffers[1] + srcOffset * 16); // 16 bits per value
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            int64_t curValue = arrayBuffer[2 * i];
            int32_t month = curValue;
            int32_t day = curValue >> (4 * sizeof(int64_t));
            int64_t micros = arrayBuffer[2 * i + 1] / 1000;
            outputVector.setValue<interval_t>(i + dstOffset, interval_t(month, day, micros));
        }
    }
}

template<typename offsetsT>
static void scanArrowArrayBLOB(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto offsets = ((const offsetsT*)array->buffers[1]) + srcOffset;
    auto arrayBuffer = (const uint8_t*)array->buffers[2];
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curOffset = offsets[i], nextOffset = offsets[i + 1];
            const uint8_t* data = arrayBuffer + curOffset;
            auto length = nextOffset - curOffset;
            BlobVector::addBlob(&outputVector, i + dstOffset, data, length);
        }
    }
}

static void scanArrowArrayBLOBView(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset, uint64_t count) {
    auto arrayBuffer = (const uint8_t*)(array->buffers[1]);
    auto valueBuffs = (const uint8_t**)(array->buffers + 2);
    // BLOB value buffers begin from index 2 onwards
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curView = (const int32_t*)(arrayBuffer + (i + srcOffset) * 16);
            // view structures are 16 bytes long
            auto viewLength = curView[0];
            if (viewLength <= 12) {
                BlobVector::addBlob(&outputVector, i + dstOffset, (uint8_t*)(curView + 1),
                    viewLength);
            } else {
                auto bufIndex = curView[2];
                auto offset = curView[3];
                BlobVector::addBlob(&outputVector, i + dstOffset, valueBuffs[bufIndex] + offset,
                    viewLength);
            }
        }
    }
}

static void scanArrowArrayFixedBLOB(const ArrowArray* array, ValueVector& outputVector,
    ArrowNullMaskTree* mask, int64_t BLOBsize, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto arrayBuffer = ((const uint8_t*)array->buffers[1]) + srcOffset * BLOBsize;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            BlobVector::addBlob(&outputVector, i + dstOffset, arrayBuffer + i * BLOBsize, BLOBsize);
        }
    }
}

template<typename offsetsT>
static void scanArrowArrayList(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto offsets = ((const offsetsT*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    uint64_t auxDstPosition = 0;
    for (uint64_t i = 0; i < count; i++) {
        auto curOffset = offsets[i], nextOffset = offsets[i + 1];
        // don't check for validity, since we still need to update the offsets
        auto newEntry = ListVector::addList(&outputVector, nextOffset - curOffset);
        outputVector.setValue<list_entry_t>(i + dstOffset, newEntry);
        if (i == 0) {
            auxDstPosition = newEntry.offset;
        }
    }
    ValueVector* auxiliaryBuffer = ListVector::getDataVector(&outputVector);
    ArrowConverter::fromArrowArray(schema->children[0], array->children[0], *auxiliaryBuffer,
        mask->getChild(0), offsets[0] + array->children[0]->offset, auxDstPosition,
        offsets[count] - offsets[0]);
}

template<typename offsetsT>
static void scanArrowArrayListView(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto offsets = ((const offsetsT*)array->buffers[1]) + srcOffset;
    auto sizes = ((const offsetsT*)array->buffers[2]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    ValueVector* auxiliaryBuffer = ListVector::getDataVector(&outputVector);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto curOffset = offsets[i], size = sizes[i];
            auto newEntry = ListVector::addList(&outputVector, size);
            outputVector.setValue<list_entry_t>(i + dstOffset, newEntry);
            ArrowNullMaskTree childTree(schema->children[0], array->children[0], srcOffset, count);
            // make our own child here. precomputing through the mask tree is too complicated
            ArrowConverter::fromArrowArray(schema->children[0], array->children[0],
                *auxiliaryBuffer, &childTree, curOffset, newEntry.offset, newEntry.size);
        }
    }
}

static void scanArrowArrayFixedList(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    mask->copyToValueVector(&outputVector, dstOffset, count);
    auto numElements = ArrayType::getNumElements(outputVector.dataType);
    for (auto i = 0u; i < count; ++i) {
        auto newEntry = ListVector::addList(&outputVector, numElements);
        outputVector.setValue<list_entry_t>(i + dstOffset, newEntry);
    }
    auto auxiliaryBuffer = ListVector::getDataVector(&outputVector);
    ArrowConverter::fromArrowArray(schema->children[0], array->children[0], *auxiliaryBuffer,
        mask->getChild(0), srcOffset * numElements + array->children[0]->offset,
        dstOffset * numElements, count * numElements);
}

static void scanArrowArrayStruct(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            outputVector.setValue<int64_t>(i + dstOffset,
                i + dstOffset); // struct_entry_t doesn't work for some reason
        }
    }
    for (int64_t j = 0; j < schema->n_children; j++) {
        ArrowConverter::fromArrowArray(schema->children[j], array->children[j],
            *StructVector::getFieldVector(&outputVector, j).get(), mask->getChild(j),
            srcOffset + array->children[j]->offset, dstOffset, count);
    }
}

static void scanArrowArrayDenseUnion(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto types = ((const uint8_t*)array->buffers[0]) + srcOffset;
    auto dstTypes = (uint16_t*)UnionVector::getTagVector(&outputVector)->getData();
    auto offsets = ((const int32_t*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    std::vector<int32_t> firstIncident(array->n_children, INT32_MAX);
    for (auto i = 0u; i < count; i++) {
        auto curType = types[i];
        auto curOffset = offsets[i];
        if (curOffset < firstIncident[curType]) {
            firstIncident[curType] = curOffset;
        }
        if (!mask->isNull(i)) {
            dstTypes[i] = curType;
            auto childOffset =
                mask->getChild(curType)->offsetBy(curOffset - firstIncident[curType]);
            ArrowConverter::fromArrowArray(schema->children[curType], array->children[curType],
                *UnionVector::getValVector(&outputVector, curType), &childOffset,
                curOffset + array->children[curType]->offset, i + dstOffset, 1);
            // may be inefficient, since we're only scanning a single value
        }
    }
}

static void scanArrowArraySparseUnion(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    auto types = ((const uint8_t*)array->buffers[0]) + srcOffset;
    auto dstTypes = (uint16_t*)UnionVector::getTagVector(&outputVector)->getData();
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            dstTypes[i] = types[i];
        }
    }
    // it is specified that values that aren't selected in the type buffer
    // must also be semantically correct. this is why this scanning works.
    // however, there is possibly room for optimization here.
    // eg. nulling out unselected children
    for (int8_t i = 0; i < array->n_children; i++) {
        ArrowConverter::fromArrowArray(schema->children[i], array->children[i],
            *UnionVector::getValVector(&outputVector, i), mask->getChild(i),
            srcOffset + array->children[i]->offset, dstOffset, count);
    }
}

template<typename offsetsT>
static void scanArrowArrayDictionaryEncoded(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {

    auto values = ((const offsetsT*)array->buffers[1]) + srcOffset;
    mask->copyToValueVector(&outputVector, dstOffset, count);
    for (uint64_t i = 0; i < count; i++) {
        if (!mask->isNull(i)) {
            auto dictOffseted = mask->getDictionary()->offsetBy(values[i]);
            ArrowConverter::fromArrowArray(schema->dictionary, array->dictionary, outputVector,
                &dictOffseted, values[i] + array->dictionary->offset, i + dstOffset,
                1); // possibly inefficient?
        }
    }
}

static void scanArrowArrayRunEndEncoded(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {

    const ArrowArray* runEndArray = array->children[0];
    auto runEndBuffer = (const uint32_t*)runEndArray->buffers[1];

    // binary search run end corresponding to srcOffset
    auto runEndIdx = runEndArray->offset;
    {
        auto L = runEndArray->offset, H = L + runEndArray->length;
        while (H >= L) {
            auto M = (H + L) >> 1;
            if (runEndBuffer[M] < srcOffset) {
                runEndIdx = M;
                H = M - 1;
            } else {
                L = M + 1;
            }
        }
    }

    for (uint64_t i = 0; i < count; i++) {
        while (i + srcOffset >= runEndBuffer[runEndIdx + 1]) {
            runEndIdx++;
        }
        auto valuesOffseted = mask->getChild(1)->offsetBy(runEndIdx);
        ArrowConverter::fromArrowArray(schema->children[1], array->children[1], outputVector,
            &valuesOffseted, runEndIdx, i + dstOffset,
            1); // there is optimization to be made here...
    }
}

void ArrowConverter::fromArrowArray(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector, ArrowNullMaskTree* mask, uint64_t srcOffset, uint64_t dstOffset,
    uint64_t count) {
    const auto arrowType = schema->format;
    if (array->dictionary != nullptr) {
        switch (arrowType[0]) {
        case 'c':
            return scanArrowArrayDictionaryEncoded<int8_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 'C':
            return scanArrowArrayDictionaryEncoded<uint8_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 's':
            return scanArrowArrayDictionaryEncoded<int16_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 'S':
            return scanArrowArrayDictionaryEncoded<uint16_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 'i':
            return scanArrowArrayDictionaryEncoded<int32_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 'I':
            return scanArrowArrayDictionaryEncoded<uint32_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 'l':
            return scanArrowArrayDictionaryEncoded<int64_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        case 'L':
            return scanArrowArrayDictionaryEncoded<uint64_t>(schema, array, outputVector, mask,
                srcOffset, dstOffset, count);
        default:
            throw RuntimeException("Invalid Index Type: " + std::string(arrowType));
        }
    }
    switch (arrowType[0]) {
    case 'n':
        // NULL
        outputVector.setAllNull();
        return;
    case 'b':
        // BOOL
        return scanArrowArrayFixedSizePrimitive<bool>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'c':
        // INT8
        return scanArrowArrayFixedSizePrimitive<int8_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'C':
        // UINT8
        return scanArrowArrayFixedSizePrimitive<uint8_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 's':
        // INT16
        return scanArrowArrayFixedSizePrimitive<int16_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'S':
        // UINT16
        return scanArrowArrayFixedSizePrimitive<uint16_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'i':
        // INT32
        return scanArrowArrayFixedSizePrimitive<int32_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'I':
        // UINT32
        return scanArrowArrayFixedSizePrimitive<uint32_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'l':
        // INT64
        return scanArrowArrayFixedSizePrimitive<int64_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'L':
        // UINT64
        return scanArrowArrayFixedSizePrimitive<uint64_t>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'f':
        // FLOAT
        return scanArrowArrayFixedSizePrimitive<float>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'g':
        // DOUBLE
        return scanArrowArrayFixedSizePrimitive<double>(array, outputVector, mask, srcOffset,
            dstOffset, count);
    case 'z':
        // BLOB
        return scanArrowArrayBLOB<int32_t>(array, outputVector, mask, srcOffset, dstOffset, count);
    case 'Z':
        // LONG BLOB
        return scanArrowArrayBLOB<int64_t>(array, outputVector, mask, srcOffset, dstOffset, count);
    case 'u':
        // STRING
        return scanArrowArrayBLOB<int32_t>(array, outputVector, mask, srcOffset, dstOffset, count);
    case 'U':
        // LONG STRING
        return scanArrowArrayBLOB<int64_t>(array, outputVector, mask, srcOffset, dstOffset, count);
    case 'v':
        switch (arrowType[1]) {
        case 'z':
            // BINARY VIEW
        case 'u':
            // STRING VIEW
            return scanArrowArrayBLOBView(array, outputVector, mask, srcOffset, dstOffset, count);
        default:
            KU_UNREACHABLE;
        }
    case 'd': {
        switch (outputVector.dataType.getPhysicalType()) {
        case PhysicalTypeID::INT16:
            return scanArrowArrayFixedSizePrimitiveAndCastTo<int128_t, int16_t>(array, outputVector,
                mask, srcOffset, dstOffset, count);
        case PhysicalTypeID::INT32:
            return scanArrowArrayFixedSizePrimitiveAndCastTo<int128_t, int32_t>(array, outputVector,
                mask, srcOffset, dstOffset, count);
        case PhysicalTypeID::INT64:
            return scanArrowArrayFixedSizePrimitiveAndCastTo<int128_t, int64_t>(array, outputVector,
                mask, srcOffset, dstOffset, count);
        case PhysicalTypeID::INT128:
            return scanArrowArrayFixedSizePrimitive<int128_t>(array, outputVector, mask, srcOffset,
                dstOffset, count);
        default:
            KU_UNREACHABLE;
        }
    }
    case 'w':
        // FIXED BLOB
        return scanArrowArrayFixedBLOB(array, outputVector, mask, std::stoi(arrowType + 2),
            srcOffset, dstOffset, count);
    case 't':
        switch (arrowType[1]) {
        case 'd':
            // DATE
            if (arrowType[2] == 'D') {
                // days since unix epoch
                return scanArrowArrayFixedSizePrimitive<int32_t>(array, outputVector, mask,
                    srcOffset, dstOffset, count);
            } else {
                // ms since unix epoch
                return scanArrowArrayFixedSizePrimitive<int64_t>(array, outputVector, mask,
                    srcOffset, dstOffset, count);
            }
        case 't':
            // TODO pure time type
            KU_UNREACHABLE;
        case 's':
            // TIMESTAMP
            return scanArrowArrayFixedSizePrimitive<int64_t>(array, outputVector, mask, srcOffset,
                dstOffset, count);
        case 'D':
            // DURATION (LBUG INTERVAL)
            switch (arrowType[2]) {
            case 's':
                // consider implement overflow checking here?
                return scanArrowArrayDurationScaledUp(array, outputVector, mask, 1000000, srcOffset,
                    dstOffset, count);
            case 'm':
                return scanArrowArrayDurationScaledUp(array, outputVector, mask, 1000, srcOffset,
                    dstOffset, count);
            case 'u':
                return scanArrowArrayDurationScaledUp(array, outputVector, mask, 1, srcOffset,
                    dstOffset, count);
            case 'n':
                return scanArrowArrayDurationScaledDown(array, outputVector, mask, 1000, srcOffset,
                    dstOffset, count);
            default:
                KU_UNREACHABLE;
            }
        case 'i':
            // INTERVAL
            switch (arrowType[2]) {
            case 'M':
                return scanArrowArrayMonthInterval(array, outputVector, mask, srcOffset, dstOffset,
                    count);
            case 'D':
                return scanArrowArrayDayTimeInterval(array, outputVector, mask, srcOffset,
                    dstOffset, count);
            case 'n':
                return scanArrowArrayMonthDayNanoInterval(array, outputVector, mask, srcOffset,
                    dstOffset, count);
            default:
                KU_UNREACHABLE;
            }
        default:
            KU_UNREACHABLE;
        }
    case '+':
        switch (arrowType[1]) {
        case 'r':
            // RUN END ENCODED
            return scanArrowArrayRunEndEncoded(schema, array, outputVector, mask, srcOffset,
                dstOffset, count);
        case 'l':
            // LIST
            return scanArrowArrayList<int32_t>(schema, array, outputVector, mask, srcOffset,
                dstOffset, count);
        case 'L':
            // LONG LIST
            return scanArrowArrayList<int64_t>(schema, array, outputVector, mask, srcOffset,
                dstOffset, count);
        case 'w': {
            // ARRAY
            RUNTIME_CHECK({
                auto arrowNumElements = std::stoul(arrowType + 3);
                auto outputNumElements = ArrayType::getNumElements(outputVector.dataType);
                KU_ASSERT(arrowNumElements == outputNumElements);
            });
            return scanArrowArrayFixedList(schema, array, outputVector, mask, srcOffset, dstOffset,
                count);
        }
        case 's':
            // STRUCT
            return scanArrowArrayStruct(schema, array, outputVector, mask, srcOffset, dstOffset,
                count);
        case 'm':
            // MAP
            return scanArrowArrayList<int32_t>(schema, array, outputVector, mask, srcOffset,
                dstOffset, count);
        case 'u':
            if (arrowType[2] == 'd') {
                // DENSE UNION
                return scanArrowArrayDenseUnion(schema, array, outputVector, mask, srcOffset,
                    dstOffset, count);
            } else {
                // SPARSE UNION
                return scanArrowArraySparseUnion(schema, array, outputVector, mask, srcOffset,
                    dstOffset, count);
            }
        case 'v':
            switch (arrowType[2]) {
            case 'l':
                return scanArrowArrayListView<int32_t>(schema, array, outputVector, mask, srcOffset,
                    dstOffset, count);
            case 'L':
                return scanArrowArrayListView<int64_t>(schema, array, outputVector, mask, srcOffset,
                    dstOffset, count);
                // LONG LIST VIEW
            default:
                KU_UNREACHABLE;
            }
        default:
            KU_UNREACHABLE;
        }
    default:
        KU_UNREACHABLE;
    }
}

void ArrowConverter::fromArrowArray(const ArrowSchema* schema, const ArrowArray* array,
    ValueVector& outputVector) {
    ArrowNullMaskTree mask(schema, array, array->offset, array->length);
    return fromArrowArray(schema, array, outputVector, &mask, array->offset, 0, array->length);
}

} // namespace common
} // namespace lbug
