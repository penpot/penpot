#pragma once

#include <stdexcept>
#include <utility>

#include "common/api.h"
#include "common/types/date_t.h"
#include "common/types/int128_t.h"
#include "common/types/interval_t.h"
#include "common/types/ku_list.h"
#include "common/types/timestamp_t.h"
#include "common/types/uint128_t.h"
#include "common/types/uuid.h"

namespace lbug {
namespace common {

class NodeVal;
class RelVal;
struct FileInfo;
class NestedVal;
class RecursiveRelVal;
class ArrowRowBatch;
class ValueVector;
class Serializer;
class Deserializer;

class Value {
    friend class NodeVal;
    friend class RelVal;
    friend class NestedVal;
    friend class RecursiveRelVal;
    friend class ArrowRowBatch;
    friend class ValueVector;

public:
    /**
     * @return a NULL value of ANY type.
     */
    LBUG_API static Value createNullValue();
    /**
     * @param dataType the type of the NULL value.
     * @return a NULL value of the given type.
     */
    LBUG_API static Value createNullValue(const LogicalType& dataType);
    /**
     * @param dataType the type of the non-NULL value.
     * @return a default non-NULL value of the given type.
     */
    LBUG_API static Value createDefaultValue(const LogicalType& dataType);
    /**
     * @param val_ the boolean value to set.
     */
    LBUG_API explicit Value(bool val_);
    /**
     * @param val_ the int8_t value to set.
     */
    LBUG_API explicit Value(int8_t val_);
    /**
     * @param val_ the int16_t value to set.
     */
    LBUG_API explicit Value(int16_t val_);
    /**
     * @param val_ the int32_t value to set.
     */
    LBUG_API explicit Value(int32_t val_);
    /**
     * @param val_ the int64_t value to set.
     */
    LBUG_API explicit Value(int64_t val_);
    /**
     * @param val_ the uint8_t value to set.
     */
    LBUG_API explicit Value(uint8_t val_);
    /**
     * @param val_ the uint16_t value to set.
     */
    LBUG_API explicit Value(uint16_t val_);
    /**
     * @param val_ the uint32_t value to set.
     */
    LBUG_API explicit Value(uint32_t val_);
    /**
     * @param val_ the uint64_t value to set.
     */
    LBUG_API explicit Value(uint64_t val_);
    /**
     * @param val_ the int128_t value to set.
     */
    LBUG_API explicit Value(int128_t val_);
    /**
     * @param val_ the UUID value to set.
     */
    LBUG_API explicit Value(ku_uuid_t val_);
    /**
     * @param val_ the double value to set.
     */
    LBUG_API explicit Value(double val_);
    /**
     * @param val_ the float value to set.
     */
    LBUG_API explicit Value(float val_);
    /**
     * @param val_ the date value to set.
     */
    LBUG_API explicit Value(date_t val_);
    /**
     * @param val_ the timestamp_ns value to set.
     */
    LBUG_API explicit Value(timestamp_ns_t val_);
    /**
     * @param val_ the timestamp_ms value to set.
     */
    LBUG_API explicit Value(timestamp_ms_t val_);
    /**
     * @param val_ the timestamp_sec value to set.
     */
    LBUG_API explicit Value(timestamp_sec_t val_);
    /**
     * @param val_ the timestamp_tz value to set.
     */
    LBUG_API explicit Value(timestamp_tz_t val_);
    /**
     * @param val_ the timestamp value to set.
     */
    LBUG_API explicit Value(timestamp_t val_);
    /**
     * @param val_ the interval value to set.
     */
    LBUG_API explicit Value(interval_t val_);
    /**
     * @param val_ the internalID value to set.
     */
    LBUG_API explicit Value(internalID_t val_);
    /**
     * @param val_ the uint128_t value to set.
     */
    LBUG_API explicit Value(uint128_t val_);
    /**
     * @param val_ the string value to set.
     */
    LBUG_API explicit Value(const char* val_);
    /**
     * @param val_ the string value to set.
     */
    LBUG_API explicit Value(const std::string& val_);
    /**
     * @param val_ the uint8_t* value to set.
     */
    LBUG_API explicit Value(uint8_t* val_);
    /**
     * @param type the logical type of the value.
     * @param val_ the string value to set.
     */
    LBUG_API explicit Value(LogicalType type, std::string val_);
    /**
     * @param dataType the logical type of the value.
     * @param children a vector of children values.
     */
    LBUG_API explicit Value(LogicalType dataType, std::vector<std::unique_ptr<Value>> children);
    /**
     * @param other the value to copy from.
     */
    LBUG_API Value(const Value& other);

    /**
     * @param other the value to move from.
     */
    LBUG_API Value(Value&& other) = default;
    LBUG_API Value& operator=(Value&& other) = default;
    LBUG_API bool operator==(const Value& rhs) const;

    /**
     * @brief Sets the data type of the Value.
     * @param dataType_ the data type to set to.
     */
    LBUG_API void setDataType(const LogicalType& dataType_);
    /**
     * @return the dataType of the value.
     */
    LBUG_API const LogicalType& getDataType() const;
    /**
     * @brief Sets the null flag of the Value.
     * @param flag null value flag to set.
     */
    LBUG_API void setNull(bool flag);
    /**
     * @brief Sets the null flag of the Value to true.
     */
    LBUG_API void setNull();
    /**
     * @return whether the Value is null or not.
     */
    LBUG_API bool isNull() const;
    /**
     * @brief Copies from the row layout value.
     * @param value value to copy from.
     */
    LBUG_API void copyFromRowLayout(const uint8_t* value);
    /**
     * @brief Copies from the col layout value.
     * @param value value to copy from.
     */
    LBUG_API void copyFromColLayout(const uint8_t* value, ValueVector* vec = nullptr);
    /**
     * @brief Copies from the other.
     * @param other value to copy from.
     */
    LBUG_API void copyValueFrom(const Value& other);
    /**
     * @return the value of the given type.
     */
    template<class T>
    T getValue() const {
        throw std::runtime_error("Unimplemented template for Value::getValue()");
    }
    /**
     * @return a reference to the value of the given type.
     */
    template<class T>
    T& getValueReference() {
        throw std::runtime_error("Unimplemented template for Value::getValueReference()");
    }
    /**
     * @return a Value object based on value.
     */
    template<class T>
    static Value createValue(T /*value*/) {
        throw std::runtime_error("Unimplemented template for Value::createValue()");
    }

    /**
     * @return a copy of the current value.
     */
    LBUG_API std::unique_ptr<Value> copy() const;
    /**
     * @return the current value in string format.
     */
    LBUG_API std::string toString() const;

    LBUG_API void serialize(Serializer& serializer) const;

    LBUG_API static std::unique_ptr<Value> deserialize(Deserializer& deserializer);

    LBUG_API void validateType(common::LogicalTypeID targetTypeID) const;

    bool hasNoneNullChildren() const;
    bool allowTypeChange() const;

    uint64_t computeHash() const;

    uint32_t getChildrenSize() const { return childrenSize; }

private:
    Value();
    explicit Value(const LogicalType& dataType);

    void resizeChildrenVector(uint64_t size, const LogicalType& childType);
    void copyFromRowLayoutList(const ku_list_t& list, const LogicalType& childType);
    void copyFromColLayoutList(const list_entry_t& list, ValueVector* vec);
    void copyFromRowLayoutStruct(const uint8_t* kuStruct);
    void copyFromColLayoutStruct(const struct_entry_t& structEntry, ValueVector* vec);
    void copyFromUnion(const uint8_t* kuUnion);

    std::string mapToString() const;
    std::string listToString() const;
    std::string structToString() const;
    std::string nodeToString() const;
    std::string relToString() const;
    std::string decimalToString() const;

public:
    union Val {
        constexpr Val() : booleanVal{false} {}
        bool booleanVal;
        int128_t int128Val;
        int64_t int64Val;
        int32_t int32Val;
        int16_t int16Val;
        int8_t int8Val;
        uint64_t uint64Val;
        uint32_t uint32Val;
        uint16_t uint16Val;
        uint8_t uint8Val;
        double doubleVal;
        float floatVal;
        // TODO(Ziyi): Should we remove the val suffix from all values in Val? Looks redundant.
        uint8_t* pointer;
        interval_t intervalVal;
        internalID_t internalIDVal;
        uint128_t uint128Val;
    } val;
    std::string strVal;

private:
    LogicalType dataType;
    bool isNull_;

    // Note: ALWAYS use childrenSize over children.size(). We do NOT resize children when
    // iterating with nested value. So children.size() reflects the capacity() rather the actual
    // size.
    std::vector<std::unique_ptr<Value>> children;
    uint32_t childrenSize;
};

/**
 * @return boolean value.
 */
template<>
inline bool Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::BOOL);
    return val.booleanVal;
}

/**
 * @return int8 value.
 */
template<>
inline int8_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT8);
    return val.int8Val;
}

/**
 * @return int16 value.
 */
template<>
inline int16_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT16);
    return val.int16Val;
}

/**
 * @return int32 value.
 */
template<>
inline int32_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT32);
    return val.int32Val;
}

/**
 * @return int64 value.
 */
template<>
inline int64_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT64);
    return val.int64Val;
}

/**
 * @return uint64 value.
 */
template<>
inline uint64_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT64);
    return val.uint64Val;
}

/**
 * @return uint32 value.
 */
template<>
inline uint32_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT32);
    return val.uint32Val;
}

/**
 * @return uint16 value.
 */
template<>
inline uint16_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT16);
    return val.uint16Val;
}

/**
 * @return uint8 value.
 */
template<>
inline uint8_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT8);
    return val.uint8Val;
}

/**
 * @return int128 value.
 */
template<>
inline int128_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT128);
    return val.int128Val;
}

/**
 * @return float value.
 */
template<>
inline float Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::FLOAT);
    return val.floatVal;
}

/**
 * @return double value.
 */
template<>
inline double Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::DOUBLE);
    return val.doubleVal;
}

/**
 * @return date_t value.
 */
template<>
inline date_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::DATE);
    return date_t{val.int32Val};
}

/**
 * @return timestamp_t value.
 */
template<>
inline timestamp_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP);
    return timestamp_t{val.int64Val};
}

/**
 * @return timestamp_ns_t value.
 */
template<>
inline timestamp_ns_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_NS);
    return timestamp_ns_t{val.int64Val};
}

/**
 * @return timestamp_ms_t value.
 */
template<>
inline timestamp_ms_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_MS);
    return timestamp_ms_t{val.int64Val};
}

/**
 * @return timestamp_sec_t value.
 */
template<>
inline timestamp_sec_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_SEC);
    return timestamp_sec_t{val.int64Val};
}

/**
 * @return timestamp_tz_t value.
 */
template<>
inline timestamp_tz_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_TZ);
    return timestamp_tz_t{val.int64Val};
}

/**
 * @return interval_t value.
 */
template<>
inline interval_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::INTERVAL);
    return val.intervalVal;
}

/**
 * @return internal_t value.
 */
template<>
inline internalID_t Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::INTERNAL_ID);
    return val.internalIDVal;
}

/**
 * @return uint128 value.
 */
template<>
inline uint128_t Value::getValue() const {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT128);
    return val.uint128Val;
}

/**
 * @return string value.
 */
template<>
inline std::string Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::STRING ||
              dataType.getLogicalTypeID() == LogicalTypeID::BLOB ||
              dataType.getLogicalTypeID() == LogicalTypeID::UUID);
    return strVal;
}

/**
 * @return uint8_t* value.
 */
template<>
inline uint8_t* Value::getValue() const {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::POINTER);
    return val.pointer;
}

/**
 * @return the reference to the boolean value.
 */
template<>
inline bool& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::BOOL);
    return val.booleanVal;
}

/**
 * @return the reference to the int8 value.
 */
template<>
inline int8_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT8);
    return val.int8Val;
}

/**
 * @return the reference to the int16 value.
 */
template<>
inline int16_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT16);
    return val.int16Val;
}

/**
 * @return the reference to the int32 value.
 */
template<>
inline int32_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT32);
    return val.int32Val;
}

/**
 * @return the reference to the int64 value.
 */
template<>
inline int64_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT64);
    return val.int64Val;
}

/**
 * @return the reference to the uint8 value.
 */
template<>
inline uint8_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT8);
    return val.uint8Val;
}

/**
 * @return the reference to the uint16 value.
 */
template<>
inline uint16_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT16);
    return val.uint16Val;
}

/**
 * @return the reference to the uint32 value.
 */
template<>
inline uint32_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT32);
    return val.uint32Val;
}

/**
 * @return the reference to the uint64 value.
 */
template<>
inline uint64_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT64);
    return val.uint64Val;
}

/**
 * @return the reference to the int128 value.
 */
template<>
inline int128_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::INT128);
    return val.int128Val;
}

/**
 * @return the reference to the float value.
 */
template<>
inline float& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::FLOAT);
    return val.floatVal;
}

/**
 * @return the reference to the double value.
 */
template<>
inline double& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::DOUBLE);
    return val.doubleVal;
}

/**
 * @return the reference to the date value.
 */
template<>
inline date_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::DATE);
    return *reinterpret_cast<date_t*>(&val.int32Val);
}

/**
 * @return the reference to the timestamp value.
 */
template<>
inline timestamp_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP);
    return *reinterpret_cast<timestamp_t*>(&val.int64Val);
}

/**
 * @return the reference to the timestamp_ms value.
 */
template<>
inline timestamp_ms_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_MS);
    return *reinterpret_cast<timestamp_ms_t*>(&val.int64Val);
}

/**
 * @return the reference to the timestamp_ns value.
 */
template<>
inline timestamp_ns_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_NS);
    return *reinterpret_cast<timestamp_ns_t*>(&val.int64Val);
}

/**
 * @return the reference to the timestamp_sec value.
 */
template<>
inline timestamp_sec_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_SEC);
    return *reinterpret_cast<timestamp_sec_t*>(&val.int64Val);
}

/**
 * @return the reference to the timestamp_tz value.
 */
template<>
inline timestamp_tz_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::TIMESTAMP_TZ);
    return *reinterpret_cast<timestamp_tz_t*>(&val.int64Val);
}

/**
 * @return the reference to the interval value.
 */
template<>
inline interval_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::INTERVAL);
    return val.intervalVal;
}

/**
 * @return the reference to the uint128 value.
 */
template<>
inline uint128_t& Value::getValueReference() {
    KU_ASSERT(dataType.getPhysicalType() == PhysicalTypeID::UINT128);
    return val.uint128Val;
}

/**
 * @return the reference to the internal_id value.
 */
template<>
inline nodeID_t& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::INTERNAL_ID);
    return val.internalIDVal;
}

/**
 * @return the reference to the string value.
 */
template<>
inline std::string& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::STRING);
    return strVal;
}

/**
 * @return the reference to the uint8_t* value.
 */
template<>
inline uint8_t*& Value::getValueReference() {
    KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::POINTER);
    return val.pointer;
}

/**
 * @param val the boolean value
 * @return a Value with BOOL type and val value.
 */
template<>
inline Value Value::createValue(bool val) {
    return Value(val);
}

template<>
inline Value Value::createValue(int8_t val) {
    return Value(val);
}

/**
 * @param val the int16 value
 * @return a Value with INT16 type and val value.
 */
template<>
inline Value Value::createValue(int16_t val) {
    return Value(val);
}

/**
 * @param val the int32 value
 * @return a Value with INT32 type and val value.
 */
template<>
inline Value Value::createValue(int32_t val) {
    return Value(val);
}

/**
 * @param val the int64 value
 * @return a Value with INT64 type and val value.
 */
template<>
inline Value Value::createValue(int64_t val) {
    return Value(val);
}

/**
 * @param val the uint8 value
 * @return a Value with UINT8 type and val value.
 */
template<>
inline Value Value::createValue(uint8_t val) {
    return Value(val);
}

/**
 * @param val the uint16 value
 * @return a Value with UINT16 type and val value.
 */
template<>
inline Value Value::createValue(uint16_t val) {
    return Value(val);
}

/**
 * @param val the uint32 value
 * @return a Value with UINT32 type and val value.
 */
template<>
inline Value Value::createValue(uint32_t val) {
    return Value(val);
}

/**
 * @param val the uint64 value
 * @return a Value with UINT64 type and val value.
 */
template<>
inline Value Value::createValue(uint64_t val) {
    return Value(val);
}

/**
 * @param val the int128_t value
 * @return a Value with INT128 type and val value.
 */
template<>
inline Value Value::createValue(int128_t val) {
    return Value(val);
}

/**
 * @param val the double value
 * @return a Value with DOUBLE type and val value.
 */
template<>
inline Value Value::createValue(double val) {
    return Value(val);
}

/**
 * @param val the date_t value
 * @return a Value with DATE type and val value.
 */
template<>
inline Value Value::createValue(date_t val) {
    return Value(val);
}

/**
 * @param val the timestamp_t value
 * @return a Value with TIMESTAMP type and val value.
 */
template<>
inline Value Value::createValue(timestamp_t val) {
    return Value(val);
}

/**
 * @param val the interval_t value
 * @return a Value with INTERVAL type and val value.
 */
template<>
inline Value Value::createValue(interval_t val) {
    return Value(val);
}

/**
 * @param val the uint128_t value
 * @return a Value with UINT128 type and val value.
 */
template<>
inline Value Value::createValue(uint128_t val) {
    return Value(val);
}

/**
 * @param val the nodeID_t value
 * @return a Value with NODE_ID type and val value.
 */
template<>
inline Value Value::createValue(nodeID_t val) {
    return Value(val);
}

/**
 * @param val the string value
 * @return a Value with type and val value.
 */
template<>
inline Value Value::createValue(std::string val) {
    return Value(LogicalType::STRING(), std::move(val));
}

/**
 * @param value the string value
 * @return a Value with STRING type and val value.
 */
template<>
inline Value Value::createValue(const char* value) {
    return Value(LogicalType::STRING(), std::string(value));
}

/**
 * @param val the uint8_t* val
 * @return a Value with POINTER type and val val.
 */
template<>
inline Value Value::createValue(uint8_t* val) {
    return Value(val);
}

/**
 * @param val the uuid_t* val
 * @return a Value with UUID type and val val.
 */
template<>
inline Value Value::createValue(ku_uuid_t val) {
    return Value(val);
}

} // namespace common
} // namespace lbug
