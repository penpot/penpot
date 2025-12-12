#include "common/arrow/arrow_row_batch.h"

#include <cstring>

#include "common/exception/runtime.h"
#include "common/types/value/node.h"
#include "common/types/value/rel.h"
#include "common/types/value/value.h"
#include "processor/result/flat_tuple.h"
#include "storage/storage_utils.h"

namespace lbug {
namespace common {

static void resizeVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes);

ArrowRowBatch::ArrowRowBatch(const std::vector<LogicalType>& types, std::int64_t capacity,
    bool fallbackExtensionTypes)
    : numTuples{0}, fallbackExtensionTypes{fallbackExtensionTypes} {
    vectors.resize(types.size());
    for (auto i = 0u; i < types.size(); i++) {
        vectors[i] = std::make_unique<ArrowVector>();
        resizeVector(vectors[i].get(), types[i], capacity, fallbackExtensionTypes);
    }
}

static uint64_t getArrowMainBufferSize(const LogicalType& type, uint64_t capacity,
    bool fallbackExtensionTypes) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::BOOL:
        return getNumBytesForBits(capacity);
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::TIMESTAMP:
    case LogicalTypeID::TIMESTAMP_SEC:
    case LogicalTypeID::TIMESTAMP_MS:
    case LogicalTypeID::TIMESTAMP_NS:
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::INTERVAL:
    case LogicalTypeID::UINT64:
    case LogicalTypeID::INT64:
        return sizeof(int64_t) * capacity;
    case LogicalTypeID::DATE:
    case LogicalTypeID::UINT32:
    case LogicalTypeID::INT32:
        return sizeof(int32_t) * capacity;
    case LogicalTypeID::UINT16:
    case LogicalTypeID::INT16:
        return sizeof(int16_t) * capacity;
    case LogicalTypeID::UNION:
    case LogicalTypeID::UINT8:
    case LogicalTypeID::INT8:
        return sizeof(int8_t) * capacity;
    case LogicalTypeID::DECIMAL:
    case LogicalTypeID::INT128:
        return sizeof(int128_t) * capacity;
    case LogicalTypeID::DOUBLE:
        return sizeof(double) * capacity;
    case LogicalTypeID::FLOAT:
        return sizeof(float) * capacity;
    case LogicalTypeID::UUID: {
        if (!fallbackExtensionTypes) {
            return sizeof(char) * 16 * capacity;
        }
        [[fallthrough]];
    }
    case LogicalTypeID::STRING:
    case LogicalTypeID::BLOB:
    case LogicalTypeID::LIST:
    case LogicalTypeID::MAP:
        return sizeof(int32_t) * (capacity + 1);
    case LogicalTypeID::ARRAY:
    case LogicalTypeID::STRUCT:
    case LogicalTypeID::INTERNAL_ID:
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::NODE:
    case LogicalTypeID::REL:
        return 0; // no main buffer
    default:
        KU_UNREACHABLE; // should enumerate all types.
    }
}

static void resizeValidityBuffer(ArrowVector* vector, int64_t capacity) {
    vector->validity.resize(getNumBytesForBits(capacity), 0xFF);
}

static void resizeMainBuffer(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes) {
    vector->data.resize(getArrowMainBufferSize(type, capacity, fallbackExtensionTypes));
}

static void resizeBLOBOverflow(ArrowVector* vector, int64_t capacity) {
    vector->overflow.resize(capacity);
}

static void resizeUnionOverflow(ArrowVector* vector, int64_t capacity) {
    vector->overflow.resize(capacity * sizeof(int32_t));
}

static void resizeChildVectors(ArrowVector* vector, const std::vector<LogicalType>& childTypes,
    int64_t childCapacity, bool fallbackExtensionTypes) {
    for (auto i = 0u; i < childTypes.size(); i++) {
        if (i >= vector->childData.size()) {
            vector->childData.push_back(std::make_unique<ArrowVector>());
        }
        resizeVector(vector->childData[i].get(), childTypes[i], childCapacity,
            fallbackExtensionTypes);
    }
}

static void resizeGeneric(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes) {
    if (vector->capacity >= capacity) {
        return;
    }
    while (vector->capacity < capacity) {
        if (vector->capacity == 0) {
            vector->capacity = 1;
        } else {
            vector->capacity *= 2;
        }
    }
    resizeValidityBuffer(vector, vector->capacity);
    resizeMainBuffer(vector, type, vector->capacity, fallbackExtensionTypes);
}

static void resizeBLOBVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    int64_t overflowCapacity, bool fallbackExtensionTypes) {
    resizeGeneric(vector, type, capacity, fallbackExtensionTypes);
    resizeBLOBOverflow(vector, overflowCapacity);
}

static void resizeFixedListVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes) {
    resizeGeneric(vector, type, capacity, fallbackExtensionTypes);
    std::vector<LogicalType> typeVec;
    typeVec.push_back(ArrayType::getChildType(type).copy());
    resizeChildVectors(vector, typeVec, vector->capacity * ArrayType::getNumElements(type),
        fallbackExtensionTypes);
}

static void resizeListVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    int64_t childCapacity, bool fallbackExtensionTypes) {
    resizeGeneric(vector, type, capacity, fallbackExtensionTypes);
    std::vector<LogicalType> typeVec;
    typeVec.push_back(ListType::getChildType(type).copy());
    resizeChildVectors(vector, typeVec, childCapacity, fallbackExtensionTypes);
}

static void resizeStructVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes) {
    resizeGeneric(vector, type, capacity, fallbackExtensionTypes);
    std::vector<LogicalType> typeVec;
    for (auto i : StructType::getFieldTypes(type)) {
        typeVec.push_back(i->copy());
    }
    resizeChildVectors(vector, typeVec, vector->capacity, fallbackExtensionTypes);
}

static void resizeUnionVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes) {
    if (vector->capacity < capacity) {
        while (vector->capacity < capacity) {
            if (vector->capacity == 0) {
                vector->capacity = 1;
            } else {
                vector->capacity *= 2;
            }
        }
        resizeMainBuffer(vector, type, vector->capacity, fallbackExtensionTypes);
    }
    resizeUnionOverflow(vector, vector->capacity);
    std::vector<LogicalType> childTypes;
    for (auto i = 0u; i < UnionType::getNumFields(type); i++) {
        childTypes.push_back(UnionType::getFieldType(type, i).copy());
    }
    resizeChildVectors(vector, childTypes, vector->capacity, fallbackExtensionTypes);
}

static void resizeInternalIDVector(ArrowVector* vector, const LogicalType& type, int64_t capacity,
    bool fallbackExtensionTypes) {
    resizeGeneric(vector, type, capacity, fallbackExtensionTypes);
    std::vector<LogicalType> typeVec;
    typeVec.push_back(LogicalType::INT64());
    typeVec.push_back(LogicalType::INT64());
    resizeChildVectors(vector, typeVec, vector->capacity, fallbackExtensionTypes);
}

static void resizeVector(ArrowVector* vector, const LogicalType& type, std::int64_t capacity,
    bool fallbackExtensionTypes) {
    auto result = std::make_unique<ArrowVector>();
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::UUID: {
        if (fallbackExtensionTypes) {
            resizeBLOBVector(vector, type, capacity, capacity, fallbackExtensionTypes);
            return;
        }
        [[fallthrough]];
    }
    case LogicalTypeID::BOOL:
    case LogicalTypeID::DECIMAL:
    case LogicalTypeID::INT128:
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64:
    case LogicalTypeID::INT32:
    case LogicalTypeID::INT16:
    case LogicalTypeID::INT8:
    case LogicalTypeID::UINT64:
    case LogicalTypeID::UINT32:
    case LogicalTypeID::UINT16:
    case LogicalTypeID::UINT8:
    case LogicalTypeID::DOUBLE:
    case LogicalTypeID::FLOAT:
    case LogicalTypeID::DATE:
    case LogicalTypeID::TIMESTAMP_MS:
    case LogicalTypeID::TIMESTAMP_NS:
    case LogicalTypeID::TIMESTAMP_SEC:
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP:
    case LogicalTypeID::INTERVAL:
        return resizeGeneric(vector, type, capacity, fallbackExtensionTypes);
    case LogicalTypeID::BLOB:
    case LogicalTypeID::STRING:
        return resizeBLOBVector(vector, type, capacity, capacity, fallbackExtensionTypes);
    case LogicalTypeID::LIST:
    case LogicalTypeID::MAP:
        return resizeListVector(vector, type, capacity, capacity, fallbackExtensionTypes);
    case LogicalTypeID::ARRAY:
        return resizeFixedListVector(vector, type, capacity, fallbackExtensionTypes);
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::NODE:
    case LogicalTypeID::REL:
    case LogicalTypeID::STRUCT:
        return resizeStructVector(vector, type, capacity, fallbackExtensionTypes);
    case LogicalTypeID::UNION:
        return resizeUnionVector(vector, type, capacity, fallbackExtensionTypes);
    case LogicalTypeID::INTERNAL_ID:
        return resizeInternalIDVector(vector, type, capacity, fallbackExtensionTypes);
    default: {
        // LCOV_EXCL_START
        throw common::RuntimeException{
            common::stringFormat("Unsupported type: {} for arrow conversion.", type.toString())};
        // LCOV_EXCL_STOP
    }
    }
}

static void getBitPosition(std::int64_t pos, std::int64_t& bytePos, std::int64_t& bitOffset) {
    bytePos = pos >> 3;
    bitOffset = pos - (bytePos << 3);
}

static void setBitToZero(std::uint8_t* data, std::int64_t pos) {
    std::int64_t bytePos = 0, bitOffset = 0;
    getBitPosition(pos, bytePos, bitOffset);
    data[bytePos] &= ~((std::uint64_t)1 << bitOffset);
}

static void setBitToOne(std::uint8_t* data, std::int64_t pos) {
    std::int64_t bytePos = 0, bitOffset = 0;
    getBitPosition(pos, bytePos, bitOffset);
    data[bytePos] |= ((std::uint64_t)1 << bitOffset);
}

void ArrowRowBatch::appendValue(ArrowVector* vector, const Value& value,
    bool fallbackExtensionTypes) {
    if (value.isNull()) {
        copyNullValue(vector, value, vector->numValues);
    } else {
        copyNonNullValue(vector, value, vector->numValues, fallbackExtensionTypes);
    }
    vector->numValues++;
}

template<LogicalTypeID DT>
void ArrowRowBatch::templateCopyNonNullValue(ArrowVector* vector, const Value& value,
    std::int64_t pos, bool) {
    auto valSize = storage::StorageUtils::getDataTypeSize(LogicalType{DT});
    std::memcpy(vector->data.data() + pos * valSize, &value.val, valSize);
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::DECIMAL>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool) {
    auto valSize = storage::StorageUtils::getDataTypeSize(value.getDataType());
    std::memcpy(vector->data.data() + pos * 16, &value.val, valSize);
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::INTERVAL>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool) {
    auto destAddr = (int64_t*)(vector->data.data() + pos * sizeof(std::int64_t));
    auto intervalVal = value.val.intervalVal;
    *destAddr = intervalVal.micros + intervalVal.days * Interval::MICROS_PER_DAY +
                intervalVal.months * Interval::MICROS_PER_MONTH;
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::BOOL>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool) {
    if (value.val.booleanVal) {
        setBitToOne(vector->data.data(), pos);
    } else {
        setBitToZero(vector->data.data(), pos);
    }
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::STRING>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool) {
    auto offsets = (std::uint32_t*)vector->data.data();
    auto strLength = value.strVal.length();
    if (pos == 0) {
        offsets[pos] = 0;
    }
    offsets[pos + 1] = offsets[pos] + strLength;
    vector->overflow.resize(offsets[pos + 1] + 1);
    std::memcpy(vector->overflow.data() + offsets[pos], value.strVal.data(), strLength);
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::UUID>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool fallbackExtensionTypes) {
    if (!fallbackExtensionTypes) {
        auto valSize = sizeof(int128_t);
        auto val = value.val.int128Val;
        val.high ^= (int64_t(1) << 63); // MSB is stored flipped internally
        // Convert to little-endian
        auto valPtr = reinterpret_cast<int8_t*>(&val);
        for (auto i = 0u; i < valSize / 2; ++i) {
            std::swap(valPtr[i], valPtr[valSize - i - 1]);
        }
        std::memcpy(vector->data.data() + pos * valSize, &val, valSize);
    } else {
        auto offsets = (std::uint32_t*)vector->data.data();
        auto str = UUID::toString(value.val.int128Val);
        auto strLength = str.length();
        if (pos == 0) {
            offsets[pos] = 0;
        }
        offsets[pos + 1] = offsets[pos] + strLength;
        vector->overflow.resize(offsets[pos + 1]);
        std::memcpy(vector->overflow.data() + offsets[pos], str.data(), strLength);
    }
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::LIST>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool fallbackExtensionTypes) {
    auto offsets = (std::uint32_t*)vector->data.data();
    auto numElements = value.childrenSize;
    if (pos == 0) {
        offsets[pos] = 0;
    }
    offsets[pos + 1] = offsets[pos] + numElements;
    std::vector<LogicalType> typeVec;
    typeVec.push_back(ListType::getChildType(value.getDataType()).copy());
    resizeChildVectors(vector, typeVec, offsets[pos + 1] + 1, fallbackExtensionTypes);
    for (auto i = 0u; i < numElements; i++) {
        appendValue(vector->childData[0].get(), *value.children[i], fallbackExtensionTypes);
    }
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::ARRAY>(ArrowVector* vector,
    const Value& value, std::int64_t /*pos*/, bool fallbackExtensionTypes) {
    auto numElements = value.childrenSize;
    for (auto i = 0u; i < numElements; i++) {
        appendValue(vector->childData[0].get(), *value.children[i], fallbackExtensionTypes);
    }
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::MAP>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool fallbackExtensionTypes) {
    // Verify all keys are not null
    for (auto i = 0u; i < value.childrenSize; ++i) {
        if (value.children[i]->children[0]->isNull()) {
            throw RuntimeException{
                stringFormat("Cannot convert map with null key to Arrow: {}", value.toString())};
        }
    }
    return templateCopyNonNullValue<LogicalTypeID::LIST>(vector, value, pos,
        fallbackExtensionTypes);
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::STRUCT>(ArrowVector* vector,
    const Value& value, std::int64_t /*pos*/, bool fallbackExtensionTypes) {
    for (auto i = 0u; i < value.childrenSize; i++) {
        appendValue(vector->childData[i].get(), *value.children[i], fallbackExtensionTypes);
    }
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::UNION>(ArrowVector* vector,
    const Value& value, std::int64_t pos, bool fallbackExtensionTypes) {
    auto typeBuffer = (std::uint8_t*)vector->data.data();
    auto offsetsBuffer = (std::int32_t*)vector->overflow.data();
    auto& type = value.getDataType();
    for (auto i = 0u; i < UnionType::getNumFields(type); i++) {
        if (UnionType::getFieldType(type, i) == value.children[0]->dataType) {
            typeBuffer[pos] = i;
            offsetsBuffer[pos] = vector->childData[i]->numValues;
            return appendValue(vector->childData[i].get(), *value.children[0],
                fallbackExtensionTypes);
        }
    }
    KU_UNREACHABLE; // We should always be able to find a matching type
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::INTERNAL_ID>(ArrowVector* vector,
    const Value& value, std::int64_t /*pos*/, bool fallbackExtensionTypes) {
    auto nodeID = value.getValue<nodeID_t>();
    Value offsetVal((std::int64_t)nodeID.offset);
    Value tableIDVal((std::int64_t)nodeID.tableID);
    appendValue(vector->childData[0].get(), offsetVal, fallbackExtensionTypes);
    appendValue(vector->childData[1].get(), tableIDVal, fallbackExtensionTypes);
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::NODE>(ArrowVector* vector,
    const Value& value, std::int64_t /*pos*/, bool fallbackExtensionTypes) {
    appendValue(vector->childData[0].get(), *NodeVal::getNodeIDVal(&value), fallbackExtensionTypes);
    appendValue(vector->childData[1].get(), *NodeVal::getLabelVal(&value), fallbackExtensionTypes);
    std::int64_t propertyId = 2;
    auto numProperties = NodeVal::getNumProperties(&value);
    for (auto i = 0u; i < numProperties; i++) {
        auto val = NodeVal::getPropertyVal(&value, i);
        appendValue(vector->childData[propertyId].get(), *val, fallbackExtensionTypes);
        propertyId++;
    }
}

template<>
void ArrowRowBatch::templateCopyNonNullValue<LogicalTypeID::REL>(ArrowVector* vector,
    const Value& value, std::int64_t /*pos*/, bool fallbackExtensionTypes) {
    appendValue(vector->childData[0].get(), *RelVal::getSrcNodeIDVal(&value),
        fallbackExtensionTypes);
    appendValue(vector->childData[1].get(), *RelVal::getDstNodeIDVal(&value),
        fallbackExtensionTypes);
    appendValue(vector->childData[2].get(), *RelVal::getLabelVal(&value), fallbackExtensionTypes);
    appendValue(vector->childData[3].get(), *RelVal::getIDVal(&value), fallbackExtensionTypes);
    common::property_id_t propertyID = 4;
    auto numProperties = RelVal::getNumProperties(&value);
    for (auto i = 0u; i < numProperties; i++) {
        auto val = RelVal::getPropertyVal(&value, i);
        appendValue(vector->childData[propertyID].get(), *val, fallbackExtensionTypes);
        propertyID++;
    }
}

void ArrowRowBatch::copyNonNullValue(ArrowVector* vector, const Value& value, std::int64_t pos,
    bool fallbackExtensionTypes) {
    switch (value.getDataType().getLogicalTypeID()) {
    case LogicalTypeID::BOOL: {
        templateCopyNonNullValue<LogicalTypeID::BOOL>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::DECIMAL:
    case LogicalTypeID::INT128: {
        templateCopyNonNullValue<LogicalTypeID::INT128>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::UUID: {
        templateCopyNonNullValue<LogicalTypeID::UUID>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64: {
        templateCopyNonNullValue<LogicalTypeID::INT64>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::INT32: {
        templateCopyNonNullValue<LogicalTypeID::INT32>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::INT16: {
        templateCopyNonNullValue<LogicalTypeID::INT16>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::INT8: {
        templateCopyNonNullValue<LogicalTypeID::INT8>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::UINT64: {
        templateCopyNonNullValue<LogicalTypeID::UINT64>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::UINT32: {
        templateCopyNonNullValue<LogicalTypeID::UINT32>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::UINT16: {
        templateCopyNonNullValue<LogicalTypeID::UINT16>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::UINT8: {
        templateCopyNonNullValue<LogicalTypeID::UINT8>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::DOUBLE: {
        templateCopyNonNullValue<LogicalTypeID::DOUBLE>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::FLOAT: {
        templateCopyNonNullValue<LogicalTypeID::FLOAT>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::DATE: {
        templateCopyNonNullValue<LogicalTypeID::DATE>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::TIMESTAMP: {
        templateCopyNonNullValue<LogicalTypeID::TIMESTAMP>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::TIMESTAMP_TZ: {
        templateCopyNonNullValue<LogicalTypeID::TIMESTAMP_TZ>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::TIMESTAMP_NS: {
        templateCopyNonNullValue<LogicalTypeID::TIMESTAMP_NS>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::TIMESTAMP_MS: {
        templateCopyNonNullValue<LogicalTypeID::TIMESTAMP_MS>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::TIMESTAMP_SEC: {
        templateCopyNonNullValue<LogicalTypeID::TIMESTAMP_SEC>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::INTERVAL: {
        templateCopyNonNullValue<LogicalTypeID::INTERVAL>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::BLOB:
    case LogicalTypeID::STRING: {
        templateCopyNonNullValue<LogicalTypeID::STRING>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::LIST: {
        templateCopyNonNullValue<LogicalTypeID::LIST>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::ARRAY: {
        templateCopyNonNullValue<LogicalTypeID::ARRAY>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::MAP: {
        templateCopyNonNullValue<LogicalTypeID::MAP>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        templateCopyNonNullValue<LogicalTypeID::STRUCT>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::UNION: {
        templateCopyNonNullValue<LogicalTypeID::UNION>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::INTERNAL_ID: {
        templateCopyNonNullValue<LogicalTypeID::INTERNAL_ID>(vector, value, pos,
            fallbackExtensionTypes);
    } break;
    case LogicalTypeID::NODE: {
        templateCopyNonNullValue<LogicalTypeID::NODE>(vector, value, pos, fallbackExtensionTypes);
    } break;
    case LogicalTypeID::REL: {
        templateCopyNonNullValue<LogicalTypeID::REL>(vector, value, pos, fallbackExtensionTypes);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

template<LogicalTypeID DT>
void ArrowRowBatch::templateCopyNullValue(ArrowVector* vector, std::int64_t pos) {
    // TODO(Guodong): make this as a function.
    setBitToZero(vector->validity.data(), pos);
    vector->numNulls++;
}

template<>
void ArrowRowBatch::templateCopyNullValue<LogicalTypeID::STRING>(ArrowVector* vector,
    std::int64_t pos) {
    auto offsets = (std::uint32_t*)vector->data.data();
    if (pos == 0) {
        offsets[pos] = 0;
    }
    offsets[pos + 1] = offsets[pos];
    setBitToZero(vector->validity.data(), pos);
    vector->numNulls++;
}

template<>
void ArrowRowBatch::templateCopyNullValue<LogicalTypeID::LIST>(ArrowVector* vector,
    std::int64_t pos) {
    auto offsets = (std::uint32_t*)vector->data.data();
    if (pos == 0) {
        offsets[pos] = 0;
    }
    offsets[pos + 1] = offsets[pos];
    setBitToZero(vector->validity.data(), pos);
    vector->numNulls++;
}

template<>
void ArrowRowBatch::templateCopyNullValue<LogicalTypeID::MAP>(ArrowVector* vector,
    std::int64_t pos) {
    return templateCopyNullValue<LogicalTypeID::LIST>(vector, pos);
}

template<>
void ArrowRowBatch::templateCopyNullValue<LogicalTypeID::STRUCT>(ArrowVector* vector,
    std::int64_t pos) {
    setBitToZero(vector->validity.data(), pos);
    vector->numNulls++;
}

void ArrowRowBatch::copyNullValueUnion(ArrowVector* vector, const Value& value, std::int64_t pos) {
    auto typeBuffer = (std::uint8_t*)vector->data.data();
    auto offsetsBuffer = (std::int32_t*)vector->overflow.data();
    typeBuffer[pos] = 0;
    offsetsBuffer[pos] = vector->childData[0]->numValues;
    copyNullValue(vector->childData[0].get(), *value.children[0], pos);
    vector->numNulls++;
}

static void copyArrowArray(ArrowVector* vector, std::int64_t pos, uint64_t numElements) {
    setBitToZero(vector->validity.data(), pos);
    vector->numNulls++;
    auto& child = vector->childData[0];
    child->numValues += numElements;
}

void ArrowRowBatch::copyNullValue(ArrowVector* vector, const Value& value, std::int64_t pos) {
    switch (value.dataType.getLogicalTypeID()) {
    case LogicalTypeID::BOOL: {
        templateCopyNullValue<LogicalTypeID::BOOL>(vector, pos);
    } break;
    case LogicalTypeID::DECIMAL:
    case LogicalTypeID::INT128: {
        templateCopyNullValue<LogicalTypeID::INT128>(vector, pos);
    } break;
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64: {
        templateCopyNullValue<LogicalTypeID::INT64>(vector, pos);
    } break;
    case LogicalTypeID::INT32: {
        templateCopyNullValue<LogicalTypeID::INT32>(vector, pos);
    } break;
    case LogicalTypeID::INT16: {
        templateCopyNullValue<LogicalTypeID::INT16>(vector, pos);
    } break;
    case LogicalTypeID::INT8: {
        templateCopyNullValue<LogicalTypeID::INT8>(vector, pos);
    } break;
    case LogicalTypeID::UINT64: {
        templateCopyNullValue<LogicalTypeID::UINT64>(vector, pos);
    } break;
    case LogicalTypeID::UINT32: {
        templateCopyNullValue<LogicalTypeID::UINT32>(vector, pos);
    } break;
    case LogicalTypeID::UINT16: {
        templateCopyNullValue<LogicalTypeID::UINT16>(vector, pos);
    } break;
    case LogicalTypeID::UINT8: {
        templateCopyNullValue<LogicalTypeID::UINT8>(vector, pos);
    } break;
    case LogicalTypeID::DOUBLE: {
        templateCopyNullValue<LogicalTypeID::DOUBLE>(vector, pos);
    } break;
    case LogicalTypeID::FLOAT: {
        templateCopyNullValue<LogicalTypeID::FLOAT>(vector, pos);
    } break;
    case LogicalTypeID::DATE: {
        templateCopyNullValue<LogicalTypeID::DATE>(vector, pos);
    } break;
    case LogicalTypeID::TIMESTAMP_MS: {
        templateCopyNullValue<LogicalTypeID::TIMESTAMP_MS>(vector, pos);
    } break;
    case LogicalTypeID::TIMESTAMP_NS: {
        templateCopyNullValue<LogicalTypeID::TIMESTAMP_NS>(vector, pos);
    } break;
    case LogicalTypeID::TIMESTAMP_SEC: {
        templateCopyNullValue<LogicalTypeID::TIMESTAMP_SEC>(vector, pos);
    } break;
    case LogicalTypeID::TIMESTAMP_TZ: {
        templateCopyNullValue<LogicalTypeID::TIMESTAMP_TZ>(vector, pos);
    } break;
    case LogicalTypeID::TIMESTAMP: {
        templateCopyNullValue<LogicalTypeID::TIMESTAMP>(vector, pos);
    } break;
    case LogicalTypeID::INTERVAL: {
        templateCopyNullValue<LogicalTypeID::INTERVAL>(vector, pos);
    } break;
    case LogicalTypeID::UUID: {
        templateCopyNullValue<LogicalTypeID::UUID>(vector, pos);
    } break;
    case LogicalTypeID::BLOB:
    case LogicalTypeID::STRING: {
        templateCopyNullValue<LogicalTypeID::STRING>(vector, pos);
    } break;
    case LogicalTypeID::LIST: {
        templateCopyNullValue<LogicalTypeID::LIST>(vector, pos);
    } break;
    case LogicalTypeID::ARRAY: {
        copyArrowArray(vector, pos, ArrayType::getNumElements(value.dataType));
    } break;
    case LogicalTypeID::MAP: {
        templateCopyNullValue<LogicalTypeID::MAP>(vector, pos);
    } break;
    case LogicalTypeID::INTERNAL_ID: {
        templateCopyNullValue<LogicalTypeID::INTERNAL_ID>(vector, pos);
    } break;
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        templateCopyNullValue<LogicalTypeID::STRUCT>(vector, pos);
    } break;
    case LogicalTypeID::UNION: {
        copyNullValueUnion(vector, value, pos);
    } break;
    case LogicalTypeID::NODE: {
        templateCopyNullValue<LogicalTypeID::NODE>(vector, pos);
    } break;
    case LogicalTypeID::REL: {
        templateCopyNullValue<LogicalTypeID::REL>(vector, pos);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

static void releaseArrowVector(ArrowArray* array) {
    if (!array || !array->release) {
        return;
    }
    array->release = nullptr;
    auto holder = static_cast<ArrowVector*>(array->private_data);
    delete holder;
}

static std::unique_ptr<ArrowArray> createArrayFromVector(ArrowVector& vector) {
    auto result = std::make_unique<ArrowArray>();
    result->private_data = nullptr;
    result->release = releaseArrowVector;
    result->n_children = 0;
    result->offset = 0;
    result->dictionary = nullptr;
    result->buffers = vector.buffers.data();
    result->null_count = vector.numNulls;
    result->length = vector.numValues;
    result->n_buffers = 1;
    result->buffers[0] = vector.validity.data();
    if (vector.data.data() != nullptr) {
        result->n_buffers++;
        result->buffers[1] = vector.data.data();
    }
    return result;
}

template<LogicalTypeID DT>
ArrowArray* ArrowRowBatch::templateCreateArray(ArrowVector& vector, const LogicalType& /*type*/,
    bool) {
    auto result = createArrayFromVector(vector);
    vector.array = std::move(result);
    return vector.array.get();
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::STRING>(ArrowVector& vector,
    const LogicalType& /*type*/, bool) {
    auto result = createArrayFromVector(vector);
    result->n_buffers = 3;
    result->buffers[2] = vector.overflow.data();
    vector.array = std::move(result);
    return vector.array.get();
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::LIST>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    auto result = createArrayFromVector(vector);
    vector.childPointers.resize(1);
    result->children = vector.childPointers.data();
    result->n_children = 1;
    vector.childPointers[0] = convertVectorToArray(*vector.childData[0],
        ListType::getChildType(type), fallbackExtensionTypes);
    vector.array = std::move(result);
    return vector.array.get();
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::ARRAY>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    auto result = createArrayFromVector(vector);
    vector.childPointers.resize(1);
    result->n_buffers = 1;
    result->children = vector.childPointers.data();
    result->n_children = 1;
    vector.childPointers[0] = convertVectorToArray(*vector.childData[0],
        ArrayType::getChildType(type), fallbackExtensionTypes);
    vector.array = std::move(result);
    return vector.array.get();
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::MAP>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    return templateCreateArray<LogicalTypeID::LIST>(vector, type, fallbackExtensionTypes);
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::STRUCT>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    return convertStructVectorToArray(vector, type, fallbackExtensionTypes);
}

ArrowArray* ArrowRowBatch::convertStructVectorToArray(ArrowVector& vector, const LogicalType& type,
    bool fallbackExtensionTypes) {
    auto result = createArrayFromVector(vector);
    result->n_buffers = 1;
    vector.childPointers.resize(StructType::getNumFields(type));
    result->children = vector.childPointers.data();
    result->n_children = (std::int64_t)StructType::getNumFields(type);
    for (auto i = 0u; i < StructType::getNumFields(type); i++) {
        const auto& childType = StructType::getFieldType(type, i);
        vector.childPointers[i] =
            convertVectorToArray(*vector.childData[i], childType, fallbackExtensionTypes);
    }
    vector.array = std::move(result);
    return vector.array.get();
}

ArrowArray* ArrowRowBatch::convertInternalIDVectorToArray(ArrowVector& vector,
    const LogicalType& /*type*/, bool fallbackExtensionTypes) {
    auto result = createArrayFromVector(vector);
    result->n_buffers = 1;
    vector.childPointers.resize(2);
    result->children = vector.childPointers.data();
    result->n_children = 2;
    for (auto i = 0; i < 2; i++) {
        auto childType = LogicalType::INT64();
        vector.childPointers[i] =
            convertVectorToArray(*vector.childData[i], childType, fallbackExtensionTypes);
    }
    vector.array = std::move(result);
    return vector.array.get();
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::UNION>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    // since union is a special case, we make the ArrowArray ourselves instead of using
    // createArrayFromVector
    auto nChildren = UnionType::getNumFields(type);
    vector.array = std::make_unique<ArrowArray>();
    vector.array->private_data = nullptr;
    vector.array->release = releaseArrowVector;
    vector.array->n_children = nChildren;
    vector.childPointers.resize(nChildren);
    vector.array->children = vector.childPointers.data();
    vector.array->offset = 0;
    vector.array->dictionary = nullptr;
    vector.array->buffers = vector.buffers.data();
    vector.array->null_count = 0;
    vector.array->length = vector.numValues;
    vector.array->n_buffers = 2;
    vector.array->buffers[0] = vector.data.data();
    vector.array->buffers[1] = vector.overflow.data();
    for (auto i = 0u; i < nChildren; i++) {
        const auto& childType = UnionType::getFieldType(type, i);
        vector.childPointers[i] =
            convertVectorToArray(*vector.childData[i], childType, fallbackExtensionTypes);
    }
    return vector.array.get();
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::INTERNAL_ID>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    return convertInternalIDVectorToArray(vector, type, fallbackExtensionTypes);
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::NODE>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    return convertStructVectorToArray(vector, type, fallbackExtensionTypes);
}

template<>
ArrowArray* ArrowRowBatch::templateCreateArray<LogicalTypeID::REL>(ArrowVector& vector,
    const LogicalType& type, bool fallbackExtensionTypes) {
    return convertStructVectorToArray(vector, type, fallbackExtensionTypes);
}

ArrowArray* ArrowRowBatch::convertVectorToArray(ArrowVector& vector, const LogicalType& type,
    bool fallbackExtensionTypes) {
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::BOOL: {
        return templateCreateArray<LogicalTypeID::BOOL>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::DECIMAL:
    case LogicalTypeID::INT128: {
        return templateCreateArray<LogicalTypeID::INT128>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64: {
        return templateCreateArray<LogicalTypeID::INT64>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::INT32: {
        return templateCreateArray<LogicalTypeID::INT32>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::INT16: {
        return templateCreateArray<LogicalTypeID::INT16>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::INT8: {
        return templateCreateArray<LogicalTypeID::INT8>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::UINT64: {
        return templateCreateArray<LogicalTypeID::UINT64>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::UINT32: {
        return templateCreateArray<LogicalTypeID::UINT32>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::UINT16: {
        return templateCreateArray<LogicalTypeID::UINT16>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::UINT8: {
        return templateCreateArray<LogicalTypeID::UINT8>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::DOUBLE: {
        return templateCreateArray<LogicalTypeID::DOUBLE>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::FLOAT: {
        return templateCreateArray<LogicalTypeID::FLOAT>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::DATE: {
        return templateCreateArray<LogicalTypeID::DATE>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::TIMESTAMP_MS: {
        return templateCreateArray<LogicalTypeID::TIMESTAMP_MS>(vector, type,
            fallbackExtensionTypes);
    }
    case LogicalTypeID::TIMESTAMP_NS: {
        return templateCreateArray<LogicalTypeID::TIMESTAMP_NS>(vector, type,
            fallbackExtensionTypes);
    }
    case LogicalTypeID::TIMESTAMP_SEC: {
        return templateCreateArray<LogicalTypeID::TIMESTAMP_SEC>(vector, type,
            fallbackExtensionTypes);
    }
    case LogicalTypeID::TIMESTAMP_TZ: {
        return templateCreateArray<LogicalTypeID::TIMESTAMP_TZ>(vector, type,
            fallbackExtensionTypes);
    }
    case LogicalTypeID::TIMESTAMP: {
        return templateCreateArray<LogicalTypeID::TIMESTAMP>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::INTERVAL: {
        return templateCreateArray<LogicalTypeID::INTERVAL>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::UUID: {
        if (!fallbackExtensionTypes) {
            return templateCreateArray<LogicalTypeID::UUID>(vector, type, fallbackExtensionTypes);
        }
        [[fallthrough]];
    }
    case LogicalTypeID::BLOB:
    case LogicalTypeID::STRING: {
        return templateCreateArray<LogicalTypeID::STRING>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::LIST: {
        return templateCreateArray<LogicalTypeID::LIST>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::ARRAY: {
        return templateCreateArray<LogicalTypeID::ARRAY>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::MAP: {
        return templateCreateArray<LogicalTypeID::MAP>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        return templateCreateArray<LogicalTypeID::STRUCT>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::UNION: {
        return templateCreateArray<LogicalTypeID::UNION>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::INTERNAL_ID: {
        return templateCreateArray<LogicalTypeID::INTERNAL_ID>(vector, type,
            fallbackExtensionTypes);
    }
    case LogicalTypeID::NODE: {
        return templateCreateArray<LogicalTypeID::NODE>(vector, type, fallbackExtensionTypes);
    }
    case LogicalTypeID::REL: {
        return templateCreateArray<LogicalTypeID::REL>(vector, type, fallbackExtensionTypes);
    }
    default: {
        KU_UNREACHABLE;
    }
    }
}

ArrowArray ArrowRowBatch::toArray(const std::vector<LogicalType>& types) {
    auto rootHolder = std::make_unique<ArrowVector>();
    ArrowArray result{};
    rootHolder->childPointers.resize(vectors.size());
    result.children = rootHolder->childPointers.data();
    result.n_children = (std::int64_t)vectors.size();
    result.length = numTuples;
    result.n_buffers = 1;
    result.buffers = rootHolder->buffers.data(); // no actual buffer
    result.offset = 0;
    result.null_count = 0;
    result.dictionary = nullptr;
    rootHolder->childData = std::move(vectors);
    for (auto i = 0u; i < rootHolder->childData.size(); i++) {
        rootHolder->childPointers[i] =
            convertVectorToArray(*rootHolder->childData[i], types[i], fallbackExtensionTypes);
    }
    result.private_data = rootHolder.release();
    result.release = releaseArrowVector;
    return result;
}

void ArrowRowBatch::append(const processor::FlatTuple& tuple) {
    for (auto i = 0u; i < vectors.size(); i++) {
        appendValue(vectors[i].get(), tuple[i], fallbackExtensionTypes);
    }
    numTuples++;
}

} // namespace common
} // namespace lbug
