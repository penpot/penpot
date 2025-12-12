#include "common/types/value/value.h"

#include <utility>

#include "common/exception/binder.h"
#include "common/null_buffer.h"
#include "common/serializer/deserializer.h"
#include "common/serializer/serializer.h"
#include "common/type_utils.h"
#include "common/types/blob.h"
#include "common/types/ku_string.h"
#include "common/types/uuid.h"
#include "common/vector/value_vector.h"
#include "function/hash/hash_functions.h"
#include "storage/storage_utils.h"

namespace lbug {
namespace common {

bool Value::operator==(const Value& rhs) const {
    if (dataType != rhs.dataType || isNull_ != rhs.isNull_) {
        return false;
    }
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL:
        return val.booleanVal == rhs.val.booleanVal;
    case PhysicalTypeID::INT128:
        return val.int128Val == rhs.val.int128Val;
    case PhysicalTypeID::INT64:
        return val.int64Val == rhs.val.int64Val;
    case PhysicalTypeID::INT32:
        return val.int32Val == rhs.val.int32Val;
    case PhysicalTypeID::INT16:
        return val.int16Val == rhs.val.int16Val;
    case PhysicalTypeID::INT8:
        return val.int8Val == rhs.val.int8Val;
    case PhysicalTypeID::UINT64:
        return val.uint64Val == rhs.val.uint64Val;
    case PhysicalTypeID::UINT32:
        return val.uint32Val == rhs.val.uint32Val;
    case PhysicalTypeID::UINT16:
        return val.uint16Val == rhs.val.uint16Val;
    case PhysicalTypeID::UINT8:
        return val.uint8Val == rhs.val.uint8Val;
    case PhysicalTypeID::DOUBLE:
        return val.doubleVal == rhs.val.doubleVal;
    case PhysicalTypeID::FLOAT:
        return val.floatVal == rhs.val.floatVal;
    case PhysicalTypeID::POINTER:
        return val.pointer == rhs.val.pointer;
    case PhysicalTypeID::INTERVAL:
        return val.intervalVal == rhs.val.intervalVal;
    case PhysicalTypeID::INTERNAL_ID:
        return val.internalIDVal == rhs.val.internalIDVal;
    case PhysicalTypeID::UINT128:
        return val.uint128Val == rhs.val.uint128Val;
    case PhysicalTypeID::STRING:
        return strVal == rhs.strVal;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::STRUCT: {
        if (childrenSize != rhs.childrenSize) {
            return false;
        }
        for (auto i = 0u; i < childrenSize; ++i) {
            if (*children[i] != *rhs.children[i]) {
                return false;
            }
        }
        return true;
    }
    default:
        KU_UNREACHABLE;
    }
}

void Value::setDataType(const LogicalType& dataType_) {
    KU_ASSERT(allowTypeChange());
    dataType = dataType_.copy();
}

const LogicalType& Value::getDataType() const {
    return dataType;
}

void Value::setNull(bool flag) {
    isNull_ = flag;
}

void Value::setNull() {
    isNull_ = true;
}

bool Value::isNull() const {
    return isNull_;
}

std::unique_ptr<Value> Value::copy() const {
    return std::make_unique<Value>(*this);
}

Value Value::createNullValue() {
    return {};
}

Value Value::createNullValue(const LogicalType& dataType) {
    return Value(dataType);
}

Value Value::createDefaultValue(const LogicalType& dataType) {
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64:
        return Value((int64_t)0);
    case LogicalTypeID::INT32:
        return Value((int32_t)0);
    case LogicalTypeID::INT16:
        return Value((int16_t)0);
    case LogicalTypeID::INT8:
        return Value((int8_t)0);
    case LogicalTypeID::UINT64:
        return Value((uint64_t)0);
    case LogicalTypeID::UINT32:
        return Value((uint32_t)0);
    case LogicalTypeID::UINT16:
        return Value((uint16_t)0);
    case LogicalTypeID::UINT8:
        return Value((uint8_t)0);
    case LogicalTypeID::INT128:
        return Value(int128_t(0));
    case LogicalTypeID::BOOL:
        return Value(true);
    case LogicalTypeID::DOUBLE:
        return Value((double)0);
    case LogicalTypeID::DATE:
        return Value(date_t());
    case LogicalTypeID::TIMESTAMP_NS:
        return Value(timestamp_ns_t());
    case LogicalTypeID::TIMESTAMP_MS:
        return Value(timestamp_ms_t());
    case LogicalTypeID::TIMESTAMP_SEC:
        return Value(timestamp_sec_t());
    case LogicalTypeID::TIMESTAMP_TZ:
        return Value(timestamp_tz_t());
    case LogicalTypeID::TIMESTAMP:
        return Value(timestamp_t());
    case LogicalTypeID::INTERVAL:
        return Value(interval_t());
    case LogicalTypeID::INTERNAL_ID:
        return Value(nodeID_t());
    case LogicalTypeID::UINT128:
        return Value(uint128_t(0));
    case LogicalTypeID::BLOB:
        return Value(LogicalType::BLOB(), std::string(""));
    case LogicalTypeID::UUID:
        return Value(LogicalType::UUID(), std::string(""));
    case LogicalTypeID::STRING:
        return Value(LogicalType::STRING(), std::string(""));
    case LogicalTypeID::FLOAT:
        return Value((float)0);
    case LogicalTypeID::DECIMAL: {
        Value ret(dataType.copy());
        ret.val.int128Val = 0;
        ret.isNull_ = false;
        ret.childrenSize = 0;
        return ret;
    }
    case LogicalTypeID::ARRAY: {
        std::vector<std::unique_ptr<Value>> children;
        const auto& childType = ArrayType::getChildType(dataType);
        auto arraySize = ArrayType::getNumElements(dataType);
        children.reserve(arraySize);
        for (auto i = 0u; i < arraySize; ++i) {
            children.push_back(std::make_unique<Value>(createDefaultValue(childType)));
        }
        return Value(dataType.copy(), std::move(children));
    }
    case LogicalTypeID::MAP:
    case LogicalTypeID::LIST:
    case LogicalTypeID::UNION: {
        // We can't create a default value for the union since the
        // selected variant is runtime information. Default value
        // is initialized when copying (see Value::copyFromUnion).
        return Value(dataType.copy(), std::vector<std::unique_ptr<Value>>{});
    }
    case LogicalTypeID::NODE:
    case LogicalTypeID::REL:
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        std::vector<std::unique_ptr<Value>> children;
        for (auto& field : StructType::getFields(dataType)) {
            children.push_back(std::make_unique<Value>(createDefaultValue(field.getType())));
        }
        return Value(dataType.copy(), std::move(children));
    }
    case LogicalTypeID::ANY: {
        return createNullValue();
    }
    default:
        KU_UNREACHABLE;
    }
}

Value::Value(bool val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::BOOL();
    val.booleanVal = val_;
}

Value::Value(int8_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INT8();
    val.int8Val = val_;
}

Value::Value(int16_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INT16();
    val.int16Val = val_;
}

Value::Value(int32_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INT32();
    val.int32Val = val_;
}

Value::Value(int64_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INT64();
    val.int64Val = val_;
}

Value::Value(uint8_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::UINT8();
    val.uint8Val = val_;
}

Value::Value(uint16_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::UINT16();
    val.uint16Val = val_;
}

Value::Value(uint32_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::UINT32();
    val.uint32Val = val_;
}

Value::Value(uint64_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::UINT64();
    val.uint64Val = val_;
}

Value::Value(int128_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INT128();
    val.int128Val = val_;
}

Value::Value(ku_uuid_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::UUID();
    val.int128Val = val_.value;
}

Value::Value(float val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::FLOAT();
    val.floatVal = val_;
}

Value::Value(double val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::DOUBLE();
    val.doubleVal = val_;
}

Value::Value(date_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::DATE();
    val.int32Val = val_.days;
}

Value::Value(timestamp_ns_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::TIMESTAMP_NS();
    val.int64Val = val_.value;
}

Value::Value(timestamp_ms_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::TIMESTAMP_MS();
    val.int64Val = val_.value;
}

Value::Value(timestamp_sec_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::TIMESTAMP_SEC();
    val.int64Val = val_.value;
}

Value::Value(timestamp_tz_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::TIMESTAMP_TZ();
    val.int64Val = val_.value;
}

Value::Value(timestamp_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::TIMESTAMP();
    val.int64Val = val_.value;
}

Value::Value(interval_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INTERVAL();
    val.intervalVal = val_;
}

Value::Value(internalID_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::INTERNAL_ID();
    val.internalIDVal = val_;
}

Value::Value(uint128_t val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::UINT128();
    val.uint128Val = val_;
}

Value::Value(const char* val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::STRING();
    strVal = std::string(val_);
}

Value::Value(const std::string& val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::STRING();
    strVal = val_;
}

Value::Value(uint8_t* val_) : isNull_{false}, childrenSize{0} {
    dataType = LogicalType::POINTER();
    val.pointer = val_;
}

Value::Value(LogicalType type, std::string val_)
    : dataType{std::move(type)}, isNull_{false}, childrenSize{0} {
    strVal = std::move(val_);
}

Value::Value(LogicalType dataType_, std::vector<std::unique_ptr<Value>> children)
    : dataType{std::move(dataType_)}, isNull_{false} {
    this->children = std::move(children);
    childrenSize = this->children.size();
}

Value::Value(const Value& other) : isNull_{other.isNull_} {
    dataType = other.dataType.copy();
    copyValueFrom(other);
    childrenSize = other.childrenSize;
}

void Value::copyFromRowLayout(const uint8_t* value) {
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::TIMESTAMP_NS:
    case LogicalTypeID::TIMESTAMP_MS:
    case LogicalTypeID::TIMESTAMP_SEC:
    case LogicalTypeID::TIMESTAMP_TZ:
    case LogicalTypeID::TIMESTAMP:
    case LogicalTypeID::INT64: {
        val.int64Val = *((int64_t*)value);
    } break;
    case LogicalTypeID::DATE:
    case LogicalTypeID::INT32: {
        val.int32Val = *((int32_t*)value);
    } break;
    case LogicalTypeID::INT16: {
        val.int16Val = *((int16_t*)value);
    } break;
    case LogicalTypeID::INT8: {
        val.int8Val = *((int8_t*)value);
    } break;
    case LogicalTypeID::UINT64: {
        val.uint64Val = *((uint64_t*)value);
    } break;
    case LogicalTypeID::UINT32: {
        val.uint32Val = *((uint32_t*)value);
    } break;
    case LogicalTypeID::UINT16: {
        val.uint16Val = *((uint16_t*)value);
    } break;
    case LogicalTypeID::UINT8: {
        val.uint8Val = *((uint8_t*)value);
    } break;
    case LogicalTypeID::INT128: {
        val.int128Val = *((int128_t*)value);
    } break;
    case LogicalTypeID::BOOL: {
        val.booleanVal = *((bool*)value);
    } break;
    case LogicalTypeID::DOUBLE: {
        val.doubleVal = *((double*)value);
    } break;
    case LogicalTypeID::FLOAT: {
        val.floatVal = *((float*)value);
    } break;
    case LogicalTypeID::DECIMAL: {
        switch (dataType.getPhysicalType()) {
        case PhysicalTypeID::INT16:
            val.int16Val = (*(int16_t*)value);
            break;
        case PhysicalTypeID::INT32:
            val.int32Val = (*(int32_t*)value);
            break;
        case PhysicalTypeID::INT64:
            val.int64Val = (*(int64_t*)value);
            break;
        case PhysicalTypeID::INT128:
            val.int128Val = (*(int128_t*)value);
            break;
        default:
            KU_UNREACHABLE;
        }
    } break;
    case LogicalTypeID::INTERVAL: {
        val.intervalVal = *((interval_t*)value);
    } break;
    case LogicalTypeID::INTERNAL_ID: {
        val.internalIDVal = *((nodeID_t*)value);
    } break;
    case LogicalTypeID::UINT128: {
        val.uint128Val = *((uint128_t*)value);
    } break;
    case LogicalTypeID::BLOB: {
        strVal = ((blob_t*)value)->value.getAsString();
    } break;
    case LogicalTypeID::UUID: {
        val.int128Val = ((ku_uuid_t*)value)->value;
        strVal = UUID::toString(*((ku_uuid_t*)value));
    } break;
    case LogicalTypeID::STRING: {
        strVal = ((ku_string_t*)value)->getAsString();
    } break;
    case LogicalTypeID::MAP:
    case LogicalTypeID::LIST: {
        copyFromRowLayoutList(*(ku_list_t*)value, ListType::getChildType(dataType));
    } break;
    case LogicalTypeID::ARRAY: {
        copyFromRowLayoutList(*(ku_list_t*)value, ArrayType::getChildType(dataType));
    } break;
    case LogicalTypeID::UNION: {
        copyFromUnion(value);
    } break;
    case LogicalTypeID::NODE:
    case LogicalTypeID::REL:
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        copyFromRowLayoutStruct(value);
    } break;
    case LogicalTypeID::POINTER: {
        val.pointer = *((uint8_t**)value);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void Value::copyFromColLayout(const uint8_t* value, ValueVector* vector) {
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::INT64: {
        val.int64Val = *((int64_t*)value);
    } break;
    case PhysicalTypeID::INT32: {
        val.int32Val = *((int32_t*)value);
    } break;
    case PhysicalTypeID::INT16: {
        val.int16Val = *((int16_t*)value);
    } break;
    case PhysicalTypeID::INT8: {
        val.int8Val = *((int8_t*)value);
    } break;
    case PhysicalTypeID::UINT64: {
        val.uint64Val = *((uint64_t*)value);
    } break;
    case PhysicalTypeID::UINT32: {
        val.uint32Val = *((uint32_t*)value);
    } break;
    case PhysicalTypeID::UINT16: {
        val.uint16Val = *((uint16_t*)value);
    } break;
    case PhysicalTypeID::UINT8: {
        val.uint8Val = *((uint8_t*)value);
    } break;
    case PhysicalTypeID::INT128: {
        val.int128Val = *((int128_t*)value);
    } break;
    case PhysicalTypeID::BOOL: {
        val.booleanVal = *((bool*)value);
    } break;
    case PhysicalTypeID::DOUBLE: {
        val.doubleVal = *((double*)value);
    } break;
    case PhysicalTypeID::FLOAT: {
        val.floatVal = *((float*)value);
    } break;
    case PhysicalTypeID::INTERVAL: {
        val.intervalVal = *((interval_t*)value);
    } break;
    case PhysicalTypeID::STRING: {
        strVal = ((ku_string_t*)value)->getAsString();
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        copyFromColLayoutList(*(list_entry_t*)value, vector);
    } break;
    case PhysicalTypeID::STRUCT: {
        copyFromColLayoutStruct(*(struct_entry_t*)value, vector);
    } break;
    case PhysicalTypeID::INTERNAL_ID: {
        val.internalIDVal = *((nodeID_t*)value);
    } break;
    case PhysicalTypeID::UINT128: {
        val.uint128Val = *((uint128_t*)value);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void Value::copyValueFrom(const Value& other) {
    if (other.isNull()) {
        isNull_ = true;
        return;
    }
    isNull_ = false;
    KU_ASSERT(dataType == other.dataType);
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        val.booleanVal = other.val.booleanVal;
    } break;
    case PhysicalTypeID::INT64: {
        val.int64Val = other.val.int64Val;
    } break;
    case PhysicalTypeID::INT32: {
        val.int32Val = other.val.int32Val;
    } break;
    case PhysicalTypeID::INT16: {
        val.int16Val = other.val.int16Val;
    } break;
    case PhysicalTypeID::INT8: {
        val.int8Val = other.val.int8Val;
    } break;
    case PhysicalTypeID::UINT64: {
        val.uint64Val = other.val.uint64Val;
    } break;
    case PhysicalTypeID::UINT32: {
        val.uint32Val = other.val.uint32Val;
    } break;
    case PhysicalTypeID::UINT16: {
        val.uint16Val = other.val.uint16Val;
    } break;
    case PhysicalTypeID::UINT8: {
        val.uint8Val = other.val.uint8Val;
    } break;
    case PhysicalTypeID::INT128: {
        val.int128Val = other.val.int128Val;
    } break;
    case PhysicalTypeID::DOUBLE: {
        val.doubleVal = other.val.doubleVal;
    } break;
    case PhysicalTypeID::FLOAT: {
        val.floatVal = other.val.floatVal;
    } break;
    case PhysicalTypeID::INTERVAL: {
        val.intervalVal = other.val.intervalVal;
    } break;
    case PhysicalTypeID::INTERNAL_ID: {
        val.internalIDVal = other.val.internalIDVal;
    } break;
    case PhysicalTypeID::UINT128: {
        val.uint128Val = other.val.uint128Val;
    } break;
    case PhysicalTypeID::STRING: {
        strVal = other.strVal;
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::STRUCT: {
        for (auto& child : other.children) {
            children.push_back(child->copy());
        }
    } break;
    case PhysicalTypeID::POINTER: {
        val.pointer = other.val.pointer;
    } break;
    default:
        KU_UNREACHABLE;
    }
}

std::string Value::toString() const {
    if (isNull_) {
        return "";
    }
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::BOOL:
        return TypeUtils::toString(val.booleanVal);
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64:
        return TypeUtils::toString(val.int64Val);
    case LogicalTypeID::INT32:
        return TypeUtils::toString(val.int32Val);
    case LogicalTypeID::INT16:
        return TypeUtils::toString(val.int16Val);
    case LogicalTypeID::INT8:
        return TypeUtils::toString(val.int8Val);
    case LogicalTypeID::UINT64:
        return TypeUtils::toString(val.uint64Val);
    case LogicalTypeID::UINT32:
        return TypeUtils::toString(val.uint32Val);
    case LogicalTypeID::UINT16:
        return TypeUtils::toString(val.uint16Val);
    case LogicalTypeID::UINT8:
        return TypeUtils::toString(val.uint8Val);
    case LogicalTypeID::INT128:
        return TypeUtils::toString(val.int128Val);
    case LogicalTypeID::DOUBLE:
        return TypeUtils::toString(val.doubleVal);
    case LogicalTypeID::FLOAT:
        return TypeUtils::toString(val.floatVal);
    case LogicalTypeID::DECIMAL:
        return decimalToString();
    case LogicalTypeID::POINTER:
        return TypeUtils::toString((uint64_t)val.pointer);
    case LogicalTypeID::DATE:
        return TypeUtils::toString(date_t{val.int32Val});
    case LogicalTypeID::TIMESTAMP_NS:
        return TypeUtils::toString(timestamp_ns_t{val.int64Val});
    case LogicalTypeID::TIMESTAMP_MS:
        return TypeUtils::toString(timestamp_ms_t{val.int64Val});
    case LogicalTypeID::TIMESTAMP_SEC:
        return TypeUtils::toString(timestamp_sec_t{val.int64Val});
    case LogicalTypeID::TIMESTAMP_TZ:
        return TypeUtils::toString(timestamp_tz_t{val.int64Val});
    case LogicalTypeID::TIMESTAMP:
        return TypeUtils::toString(timestamp_t{val.int64Val});
    case LogicalTypeID::INTERVAL:
        return TypeUtils::toString(val.intervalVal);
    case LogicalTypeID::INTERNAL_ID:
        return TypeUtils::toString(val.internalIDVal);
    case LogicalTypeID::UINT128:
        return TypeUtils::toString(val.uint128Val);
    case LogicalTypeID::BLOB:
        return Blob::toString(reinterpret_cast<const uint8_t*>(strVal.c_str()), strVal.length());
    case LogicalTypeID::UUID:
        return UUID::toString(val.int128Val);
    case LogicalTypeID::STRING:
        return strVal;
    case LogicalTypeID::MAP: {
        return mapToString();
    }
    case LogicalTypeID::LIST:
    case LogicalTypeID::ARRAY: {
        return listToString();
    }
    case LogicalTypeID::UNION: {
        // Only one member in the union can be active at a time and that member is always stored
        // at index 0.
        return children[0]->toString();
    }
    case LogicalTypeID::RECURSIVE_REL:
    case LogicalTypeID::STRUCT: {
        return structToString();
    }
    case LogicalTypeID::NODE: {
        return nodeToString();
    }
    case LogicalTypeID::REL: {
        return relToString();
    }
    default:
        KU_UNREACHABLE;
    }
}

Value::Value() : isNull_{true}, childrenSize{0} {
    dataType = LogicalType(LogicalTypeID::ANY);
}

Value::Value(const LogicalType& dataType_) : isNull_{true}, childrenSize{0} {
    dataType = dataType_.copy();
}

void Value::resizeChildrenVector(uint64_t size, const LogicalType& childType) {
    if (size > children.size()) {
        children.reserve(size);
        for (auto i = children.size(); i < size; ++i) {
            children.push_back(std::make_unique<Value>(createDefaultValue(childType)));
        }
    }
    childrenSize = size;
}

void Value::copyFromRowLayoutList(const ku_list_t& list, const LogicalType& childType) {
    resizeChildrenVector(list.size, childType);
    auto numBytesPerElement = storage::StorageUtils::getDataTypeSize(childType);
    auto listNullBytes = reinterpret_cast<uint8_t*>(list.overflowPtr);
    auto numBytesForNullValues = NullBuffer::getNumBytesForNullValues(list.size);
    auto listValues = listNullBytes + numBytesForNullValues;
    for (auto i = 0u; i < list.size; i++) {
        auto childValue = children[i].get();
        if (NullBuffer::isNull(listNullBytes, i)) {
            childValue->setNull(true);
        } else {
            childValue->setNull(false);
            childValue->copyFromRowLayout(listValues);
        }
        listValues += numBytesPerElement;
    }
}

void Value::copyFromColLayoutList(const list_entry_t& listEntry, ValueVector* vec) {
    auto dataVec = ListVector::getDataVector(vec);
    resizeChildrenVector(listEntry.size, dataVec->dataType);
    for (auto i = 0u; i < listEntry.size; i++) {
        auto childValue = children[i].get();
        childValue->setNull(dataVec->isNull(listEntry.offset + i));
        if (!childValue->isNull()) {
            childValue->copyFromColLayout(ListVector::getListValuesWithOffset(vec, listEntry, i),
                dataVec);
        }
    }
}

void Value::copyFromRowLayoutStruct(const uint8_t* kuStruct) {
    auto numFields = childrenSize;
    auto structNullValues = kuStruct;
    auto structValues = structNullValues + NullBuffer::getNumBytesForNullValues(numFields);
    for (auto i = 0u; i < numFields; i++) {
        auto childValue = children[i].get();
        if (NullBuffer::isNull(structNullValues, i)) {
            childValue->setNull(true);
        } else {
            childValue->setNull(false);
            childValue->copyFromRowLayout(structValues);
        }
        structValues += storage::StorageUtils::getDataTypeSize(childValue->dataType);
    }
}

void Value::copyFromColLayoutStruct(const struct_entry_t& structEntry, ValueVector* vec) {
    for (auto i = 0u; i < childrenSize; i++) {
        children[i]->setNull(StructVector::getFieldVector(vec, i)->isNull(structEntry.pos));
        if (!children[i]->isNull()) {
            auto fieldVector = StructVector::getFieldVector(vec, i);
            children[i]->copyFromColLayout(fieldVector->getData() +
                                               fieldVector->getNumBytesPerValue() * structEntry.pos,
                fieldVector.get());
        }
    }
}

void Value::copyFromUnion(const uint8_t* kuUnion) {
    auto childrenTypes = StructType::getFieldTypes(dataType);
    auto unionNullValues = kuUnion;
    auto unionValues = unionNullValues + NullBuffer::getNumBytesForNullValues(childrenTypes.size());
    // For union dataType, only one member can be active at a time. So we don't need to copy all
    // union fields into value.
    auto activeFieldIdx = UnionType::getInternalFieldIdx(*(union_field_idx_t*)unionValues);
    // Create default value now that we know the active field
    auto childValue = Value::createDefaultValue(*childrenTypes[activeFieldIdx]);
    auto curMemberIdx = 0u;
    // Seek to the current active member value.
    while (curMemberIdx < activeFieldIdx) {
        unionValues += storage::StorageUtils::getDataTypeSize(*childrenTypes[curMemberIdx]);
        curMemberIdx++;
    }
    if (NullBuffer::isNull(unionNullValues, activeFieldIdx)) {
        childValue.setNull(true);
    } else {
        childValue.setNull(false);
        childValue.copyFromRowLayout(unionValues);
    }
    if (children.empty()) {
        children.push_back(std::make_unique<Value>(std::move(childValue)));
        childrenSize = 1;
    } else {
        children[0] = std::make_unique<Value>(std::move(childValue));
    }
}

void Value::serialize(Serializer& serializer) const {
    dataType.serialize(serializer);
    serializer.serializeValue(isNull_);
    serializer.serializeValue(childrenSize);

    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        serializer.serializeValue(val.booleanVal);
    } break;
    case PhysicalTypeID::INT64: {
        serializer.serializeValue(val.int64Val);
    } break;
    case PhysicalTypeID::INT32: {
        serializer.serializeValue(val.int32Val);
    } break;
    case PhysicalTypeID::INT16: {
        serializer.serializeValue(val.int16Val);
    } break;
    case PhysicalTypeID::INT8: {
        serializer.serializeValue(val.int8Val);
    } break;
    case PhysicalTypeID::UINT64: {
        serializer.serializeValue(val.uint64Val);
    } break;
    case PhysicalTypeID::UINT32: {
        serializer.serializeValue(val.uint32Val);
    } break;
    case PhysicalTypeID::UINT16: {
        serializer.serializeValue(val.uint16Val);
    } break;
    case PhysicalTypeID::UINT8: {
        serializer.serializeValue(val.uint8Val);
    } break;
    case PhysicalTypeID::INT128: {
        serializer.serializeValue(val.int128Val);
    } break;
    case PhysicalTypeID::DOUBLE: {
        serializer.serializeValue(val.doubleVal);
    } break;
    case PhysicalTypeID::FLOAT: {
        serializer.serializeValue(val.floatVal);
    } break;
    case PhysicalTypeID::INTERVAL: {
        serializer.serializeValue(val.intervalVal);
    } break;
    case PhysicalTypeID::INTERNAL_ID: {
        serializer.serializeValue(val.internalIDVal);
    } break;
    case PhysicalTypeID::UINT128: {
        serializer.serializeValue(val.uint128Val);
    } break;
    case PhysicalTypeID::STRING: {
        serializer.serializeValue(strVal);
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::STRUCT: {
        for (auto i = 0u; i < childrenSize; ++i) {
            children[i]->serialize(serializer);
        }
    } break;
    case PhysicalTypeID::ANY: {
        // We want to be able to ser/deser values that are meant to just be null
        if (!isNull_) {
            KU_UNREACHABLE;
        }
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

std::unique_ptr<Value> Value::deserialize(Deserializer& deserializer) {
    LogicalType dataType = LogicalType::deserialize(deserializer);
    std::unique_ptr<Value> val = std::make_unique<Value>(createDefaultValue(dataType));
    deserializer.deserializeValue(val->isNull_);
    deserializer.deserializeValue(val->childrenSize);
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        deserializer.deserializeValue(val->val.booleanVal);
    } break;
    case PhysicalTypeID::INT64: {
        deserializer.deserializeValue(val->val.int64Val);
    } break;
    case PhysicalTypeID::INT32: {
        deserializer.deserializeValue(val->val.int32Val);
    } break;
    case PhysicalTypeID::INT16: {
        deserializer.deserializeValue(val->val.int16Val);
    } break;
    case PhysicalTypeID::INT8: {
        deserializer.deserializeValue(val->val.int8Val);
    } break;
    case PhysicalTypeID::UINT64: {
        deserializer.deserializeValue(val->val.uint64Val);
    } break;
    case PhysicalTypeID::UINT32: {
        deserializer.deserializeValue(val->val.uint32Val);
    } break;
    case PhysicalTypeID::UINT16: {
        deserializer.deserializeValue(val->val.uint16Val);
    } break;
    case PhysicalTypeID::UINT8: {
        deserializer.deserializeValue(val->val.uint8Val);
    } break;
    case PhysicalTypeID::INT128: {
        deserializer.deserializeValue(val->val.int128Val);
    } break;
    case PhysicalTypeID::DOUBLE: {
        deserializer.deserializeValue(val->val.doubleVal);
    } break;
    case PhysicalTypeID::FLOAT: {
        deserializer.deserializeValue(val->val.floatVal);
    } break;
    case PhysicalTypeID::INTERVAL: {
        deserializer.deserializeValue(val->val.intervalVal);
    } break;
    case PhysicalTypeID::INTERNAL_ID: {
        deserializer.deserializeValue(val->val.internalIDVal);
    } break;
    case PhysicalTypeID::UINT128: {
        deserializer.deserializeValue(val->val.uint128Val);
    } break;
    case PhysicalTypeID::STRING: {
        deserializer.deserializeValue(val->strVal);
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::STRUCT: {
        val->children.resize(val->childrenSize);
        for (auto i = 0u; i < val->childrenSize; i++) {
            val->children[i] = deserialize(deserializer);
        }
    } break;
    case PhysicalTypeID::ANY: {
        // We want to be able to ser/deser values that are meant to just be null
        if (!val->isNull_) {
            KU_UNREACHABLE;
        }
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    return val;
}

void Value::validateType(LogicalTypeID targetTypeID) const {
    if (dataType.getLogicalTypeID() == targetTypeID) {
        return;
    }
    throw BinderException(stringFormat("{} has data type {} but {} was expected.", toString(),
        dataType.toString(), LogicalTypeUtils::toString(targetTypeID)));
}

bool Value::hasNoneNullChildren() const {
    for (auto i = 0u; i < childrenSize; ++i) {
        if (!children[i]->isNull()) {
            return true;
        }
    }
    return false;
}

// Handle the case of casting empty list to a different type.
bool Value::allowTypeChange() const {
    if (isNull_ || !dataType.isInternalType()) {
        return true;
    }
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::ANY:
        return true;
    case LogicalTypeID::LIST:
    case LogicalTypeID::ARRAY: {
        if (childrenSize == 0) {
            return true;
        }
        for (auto i = 0u; i < childrenSize; ++i) {
            if (children[i]->allowTypeChange()) {
                return true;
            }
        }
        return false;
    }
    case LogicalTypeID::STRUCT: {
        for (auto i = 0u; i < childrenSize; ++i) {
            if (children[i]->allowTypeChange()) {
                return true;
            }
        }
        return false;
    }
    case LogicalTypeID::MAP: {
        if (childrenSize == 0) {
            return true;
        }
        for (auto i = 0u; i < childrenSize; ++i) {
            auto k = children[i]->children[0].get();
            auto v = children[i]->children[1].get();
            if (k->allowTypeChange() || v->allowTypeChange()) {
                return true;
            }
        }
        return false;
    }
    default:
        return false;
    }
}

uint64_t Value::computeHash() const {
    if (isNull_) {
        return function::NULL_HASH;
    }
    hash_t hashValue = 0;
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        function::Hash::operation(val.booleanVal, hashValue);
    } break;
    case PhysicalTypeID::INT128: {
        function::Hash::operation(val.int128Val, hashValue);
    } break;
    case PhysicalTypeID::INT64: {
        function::Hash::operation(val.int64Val, hashValue);
    } break;
    case PhysicalTypeID::INT32: {
        function::Hash::operation(val.int32Val, hashValue);
    } break;
    case PhysicalTypeID::INT16: {
        function::Hash::operation(val.int16Val, hashValue);
    } break;
    case PhysicalTypeID::INT8: {
        function::Hash::operation(val.int8Val, hashValue);
    } break;
    case PhysicalTypeID::UINT64: {
        function::Hash::operation(val.uint64Val, hashValue);
    } break;
    case PhysicalTypeID::UINT32: {
        function::Hash::operation(val.uint32Val, hashValue);
    } break;
    case PhysicalTypeID::UINT16: {
        function::Hash::operation(val.uint16Val, hashValue);
    } break;
    case PhysicalTypeID::UINT8: {
        function::Hash::operation(val.uint8Val, hashValue);
    } break;
    case PhysicalTypeID::DOUBLE: {
        function::Hash::operation(val.doubleVal, hashValue);
    } break;
    case PhysicalTypeID::FLOAT: {
        function::Hash::operation(val.floatVal, hashValue);
    } break;
    case PhysicalTypeID::INTERVAL: {
        function::Hash::operation(val.intervalVal, hashValue);
    } break;
    case PhysicalTypeID::INTERNAL_ID: {
        function::Hash::operation(val.internalIDVal, hashValue);
    } break;
    case PhysicalTypeID::UINT128: {
        function::Hash::operation(val.uint128Val, hashValue);
    } break;
    case PhysicalTypeID::STRING: {
        function::Hash::operation(strVal, hashValue);
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST:
    case PhysicalTypeID::STRUCT: {
        if (childrenSize == 0) {
            return function::NULL_HASH;
        }
        hashValue = children[0]->computeHash();
        for (auto i = 1u; i < childrenSize; i++) {
            hashValue = function::combineHashScalar(hashValue, children[i]->computeHash());
        }
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
    return hashValue;
}

std::string Value::mapToString() const {
    std::string result = "{";
    for (auto i = 0u; i < childrenSize; ++i) {
        auto structVal = children[i].get();
        result += structVal->children[0]->toString();
        result += "=";
        result += structVal->children[1]->toString();
        result += (i == childrenSize - 1 ? "" : ", ");
    }
    result += "}";
    return result;
}

std::string Value::listToString() const {
    std::string result = "[";
    for (auto i = 0u; i < childrenSize; ++i) {
        result += children[i]->toString();
        if (i != childrenSize - 1) {
            result += ",";
        }
    }
    result += "]";
    return result;
}

std::string Value::structToString() const {
    std::string result = "{";
    auto fieldNames = StructType::getFieldNames(dataType);
    for (auto i = 0u; i < childrenSize; ++i) {
        result += fieldNames[i] + ": ";
        result += children[i]->toString();
        if (i != childrenSize - 1) {
            result += ", ";
        }
    }
    result += "}";
    return result;
}

std::string Value::nodeToString() const {
    if (children[0]->isNull_) {
        // NODE is represented as STRUCT. We don't have a way to represent STRUCT as null.
        // Instead, we check the internal ID entry to decide if a NODE is NULL.
        return "";
    }
    std::string result = "{";
    auto fieldNames = StructType::getFieldNames(dataType);
    for (auto i = 0u; i < childrenSize; ++i) {
        if (children[i]->isNull_) {
            // Avoid printing null key value pair.
            continue;
        }
        if (i != 0) {
            result += ", ";
        }
        result += fieldNames[i] + ": " + children[i]->toString();
    }
    result += "}";
    return result;
}

std::string Value::relToString() const {
    if (children[3]->isNull_) {
        // REL is represented as STRUCT. We don't have a way to represent STRUCT as null.
        // Instead, we check the internal ID entry to decide if a REL is NULL.
        return "";
    }
    std::string result = "(" + children[0]->toString() + ")-{";
    auto fieldNames = StructType::getFieldNames(dataType);
    for (auto i = 2u; i < childrenSize; ++i) {
        if (children[i]->isNull_) {
            // Avoid printing null key value pair.
            continue;
        }
        if (i != 2) {
            result += ", ";
        }
        result += fieldNames[i] + ": " + children[i]->toString();
    }
    result += "}->(" + children[1]->toString() + ")";
    return result;
}

std::string Value::decimalToString() const {
    switch (dataType.getPhysicalType()) {
    case PhysicalTypeID::INT16:
        return DecimalType::insertDecimalPoint(TypeUtils::toString(val.int16Val),
            DecimalType::getScale(dataType));
    case PhysicalTypeID::INT32:
        return DecimalType::insertDecimalPoint(TypeUtils::toString(val.int32Val),
            DecimalType::getScale(dataType));
    case PhysicalTypeID::INT64:
        return DecimalType::insertDecimalPoint(TypeUtils::toString(val.int64Val),
            DecimalType::getScale(dataType));
    case PhysicalTypeID::INT128:
        return DecimalType::insertDecimalPoint(TypeUtils::toString(val.int128Val),
            DecimalType::getScale(dataType));
    default:
        KU_UNREACHABLE;
    }
}

} // namespace common
} // namespace lbug
