#pragma once

#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "common/api.h"
#include "common/cast.h"
#include "common/copy_constructors.h"
#include "common/types/interval_t.h"

namespace lbug {
namespace main {
class ClientContext;
}
namespace processor {
class ParquetReader;
}
namespace catalog {
class NodeTableCatalogEntry;
}
namespace common {

class Serializer;
class Deserializer;
struct FileInfo;

using sel_t = uint64_t;
constexpr sel_t INVALID_SEL = UINT64_MAX;
using hash_t = uint64_t;
using page_idx_t = uint32_t;
using frame_idx_t = page_idx_t;
using page_offset_t = uint32_t;
constexpr page_idx_t INVALID_PAGE_IDX = UINT32_MAX;
using file_idx_t = uint32_t;
constexpr file_idx_t INVALID_FILE_IDX = UINT32_MAX;
using page_group_idx_t = uint32_t;
using frame_group_idx_t = page_group_idx_t;
using column_id_t = uint32_t;
using property_id_t = uint32_t;
constexpr column_id_t INVALID_COLUMN_ID = UINT32_MAX;
constexpr column_id_t ROW_IDX_COLUMN_ID = INVALID_COLUMN_ID - 1;
using idx_t = uint32_t;
constexpr idx_t INVALID_IDX = UINT32_MAX;
using block_idx_t = uint64_t;
constexpr block_idx_t INVALID_BLOCK_IDX = UINT64_MAX;
using struct_field_idx_t = uint16_t;
using union_field_idx_t = struct_field_idx_t;
constexpr struct_field_idx_t INVALID_STRUCT_FIELD_IDX = UINT16_MAX;
using row_idx_t = uint64_t;
constexpr row_idx_t INVALID_ROW_IDX = UINT64_MAX;
constexpr uint32_t UNDEFINED_CAST_COST = UINT32_MAX;
using node_group_idx_t = uint64_t;
constexpr node_group_idx_t INVALID_NODE_GROUP_IDX = UINT64_MAX;
using partition_idx_t = uint64_t;
constexpr partition_idx_t INVALID_PARTITION_IDX = UINT64_MAX;
using length_t = uint64_t;
constexpr length_t INVALID_LENGTH = UINT64_MAX;
using list_size_t = uint32_t;
using sequence_id_t = uint64_t;
using oid_t = uint64_t;
constexpr oid_t INVALID_OID = UINT64_MAX;

using transaction_t = uint64_t;
constexpr transaction_t INVALID_TRANSACTION = UINT64_MAX;
using executor_id_t = uint64_t;
using executor_info = std::unordered_map<executor_id_t, uint64_t>;

// table id type alias
using table_id_t = oid_t;
using table_id_vector_t = std::vector<table_id_t>;
using table_id_set_t = std::unordered_set<table_id_t>;
template<typename T>
using table_id_map_t = std::unordered_map<table_id_t, T>;
constexpr table_id_t INVALID_TABLE_ID = INVALID_OID;
// offset type alias
using offset_t = uint64_t;
constexpr offset_t INVALID_OFFSET = UINT64_MAX;
// internal id type alias
struct internalID_t;
using nodeID_t = internalID_t;
using relID_t = internalID_t;

using cardinality_t = uint64_t;
constexpr offset_t INVALID_LIMIT = UINT64_MAX;
using offset_vec_t = std::vector<offset_t>;
// System representation for internalID.
struct LBUG_API internalID_t {
    offset_t offset;
    table_id_t tableID;

    internalID_t();
    internalID_t(offset_t offset, table_id_t tableID);

    // comparison operators
    bool operator==(const internalID_t& rhs) const;
    bool operator!=(const internalID_t& rhs) const;
    bool operator>(const internalID_t& rhs) const;
    bool operator>=(const internalID_t& rhs) const;
    bool operator<(const internalID_t& rhs) const;
    bool operator<=(const internalID_t& rhs) const;
};

// System representation for a variable-sized overflow value.
struct overflow_value_t {
    // the size of the overflow buffer can be calculated as:
    // numElements * sizeof(Element) + nullMap(4 bytes alignment)
    uint64_t numElements = 0;
    uint8_t* value = nullptr;
};

struct list_entry_t {
    offset_t offset;
    list_size_t size;

    constexpr list_entry_t() : offset{INVALID_OFFSET}, size{UINT32_MAX} {}
    constexpr list_entry_t(offset_t offset, list_size_t size) : offset{offset}, size{size} {}
};

struct struct_entry_t {
    int64_t pos;
};

struct map_entry_t {
    list_entry_t entry;
};

struct union_entry_t {
    struct_entry_t entry;
};

struct int128_t;
struct uint128_t;
struct ku_string_t;

template<typename T>
concept SignedIntegerTypes =
    std::is_same_v<T, int8_t> || std::is_same_v<T, int16_t> || std::is_same_v<T, int32_t> ||
    std::is_same_v<T, int64_t> || std::is_same_v<T, int128_t>;

template<typename T>
concept UnsignedIntegerTypes =
    std::is_same_v<T, uint8_t> || std::is_same_v<T, uint16_t> || std::is_same_v<T, uint32_t> ||
    std::is_same_v<T, uint64_t> || std::is_same_v<T, uint128_t>;

template<typename T>
concept IntegerTypes = SignedIntegerTypes<T> || UnsignedIntegerTypes<T>;

template<typename T>
concept FloatingPointTypes = std::is_same_v<T, float> || std::is_same_v<T, double>;

template<typename T>
concept NumericTypes = IntegerTypes<T> || std::floating_point<T>;

template<typename T>
concept ComparableTypes = NumericTypes<T> || std::is_same_v<T, ku_string_t> ||
                          std::is_same_v<T, interval_t> || std::is_same_v<T, bool>;

template<typename T>
concept HashablePrimitive =
    ((std::integral<T> && !std::is_same_v<T, bool>) || std::floating_point<T> ||
        std::is_same_v<T, int128_t> || std::is_same_v<T, uint128_t>);
template<typename T>
concept IndexHashable = ((std::integral<T> && !std::is_same_v<T, bool>) || std::floating_point<T> ||
                         std::is_same_v<T, int128_t> || std::is_same_v<T, uint128_t> ||
                         std::is_same_v<T, ku_string_t> || std::is_same_v<T, std::string_view> ||
                         std::same_as<T, std::string>);

template<typename T>
concept HashableNonNestedTypes =
    (std::integral<T> || std::floating_point<T> || std::is_same_v<T, int128_t> ||
        std::is_same_v<T, uint128_t> || std::is_same_v<T, internalID_t> ||
        std::is_same_v<T, interval_t> || std::is_same_v<T, ku_string_t>);

template<typename T>
concept HashableNestedTypes =
    (std::is_same_v<T, list_entry_t> || std::is_same_v<T, struct_entry_t>);

template<typename T>
concept HashableTypes = (HashableNestedTypes<T> || HashableNonNestedTypes<T>);

enum class LogicalTypeID : uint8_t {
    ANY = 0,
    NODE = 10,
    REL = 11,
    RECURSIVE_REL = 12,
    // SERIAL is a special data type that is used to represent a sequence of INT64 values that are
    // incremented by 1 starting from 0.
    SERIAL = 13,

    BOOL = 22,
    INT64 = 23,
    INT32 = 24,
    INT16 = 25,
    INT8 = 26,
    UINT64 = 27,
    UINT32 = 28,
    UINT16 = 29,
    UINT8 = 30,
    INT128 = 31,
    DOUBLE = 32,
    FLOAT = 33,
    DATE = 34,
    TIMESTAMP = 35,
    TIMESTAMP_SEC = 36,
    TIMESTAMP_MS = 37,
    TIMESTAMP_NS = 38,
    TIMESTAMP_TZ = 39,
    INTERVAL = 40,
    DECIMAL = 41,
    INTERNAL_ID = 42,
    UINT128 = 43,

    STRING = 50,
    BLOB = 51,

    LIST = 52,
    ARRAY = 53,
    STRUCT = 54,
    MAP = 55,
    UNION = 56,
    POINTER = 58,

    UUID = 59,

};

enum class PhysicalTypeID : uint8_t {
    // Fixed size types.
    ANY = 0,
    BOOL = 1,
    INT64 = 2,
    INT32 = 3,
    INT16 = 4,
    INT8 = 5,
    UINT64 = 6,
    UINT32 = 7,
    UINT16 = 8,
    UINT8 = 9,
    INT128 = 10,
    DOUBLE = 11,
    FLOAT = 12,
    INTERVAL = 13,
    INTERNAL_ID = 14,
    ALP_EXCEPTION_FLOAT = 15,
    ALP_EXCEPTION_DOUBLE = 16,
    UINT128 = 17,

    // Variable size types.
    STRING = 20,
    LIST = 22,
    ARRAY = 23,
    STRUCT = 24,
    POINTER = 25,
};

class ExtraTypeInfo;
class StructField;
class StructTypeInfo;

enum class TypeCategory : uint8_t { INTERNAL = 0, UDT = 1 };

class LogicalType {
    friend struct LogicalTypeUtils;
    friend struct DecimalType;
    friend struct StructType;
    friend struct ListType;
    friend struct ArrayType;

    LBUG_API LogicalType(const LogicalType& other);

public:
    LogicalType() : typeID{LogicalTypeID::ANY}, extraTypeInfo{nullptr} {
        physicalType = getPhysicalType(this->typeID);
    };
    explicit LBUG_API LogicalType(LogicalTypeID typeID, TypeCategory info = TypeCategory::INTERNAL);
    EXPLICIT_COPY_DEFAULT_MOVE(LogicalType);

    LBUG_API bool operator==(const LogicalType& other) const;
    LBUG_API bool operator!=(const LogicalType& other) const;

    LBUG_API std::string toString() const;
    static bool isBuiltInType(const std::string& str);
    static LogicalType convertFromString(const std::string& str, main::ClientContext* context);

    LogicalTypeID getLogicalTypeID() const { return typeID; }
    bool containsAny() const;
    bool isInternalType() const { return category == TypeCategory::INTERNAL; }

    PhysicalTypeID getPhysicalType() const { return physicalType; }
    LBUG_API static PhysicalTypeID getPhysicalType(LogicalTypeID logicalType,
        const std::unique_ptr<ExtraTypeInfo>& extraTypeInfo = nullptr);

    void setExtraTypeInfo(std::unique_ptr<ExtraTypeInfo> typeInfo) {
        extraTypeInfo = std::move(typeInfo);
    }

    const ExtraTypeInfo* getExtraTypeInfo() const { return extraTypeInfo.get(); }

    void serialize(Serializer& serializer) const;

    static LogicalType deserialize(Deserializer& deserializer);

    LBUG_API static std::vector<LogicalType> copy(const std::vector<LogicalType>& types);
    LBUG_API static std::vector<LogicalType> copy(const std::vector<LogicalType*>& types);

    static LogicalType ANY() { return LogicalType(LogicalTypeID::ANY); }

    // NOTE: avoid using this if possible, this is a temporary hack for passing internal types
    // TODO(Royi) remove this when float compression no longer relies on this or ColumnChunkData
    // takes physical types instead of logical types
    static LogicalType ANY(PhysicalTypeID physicalType) {
        auto ret = LogicalType(LogicalTypeID::ANY);
        ret.physicalType = physicalType;
        return ret;
    }

    static LogicalType BOOL() { return LogicalType(LogicalTypeID::BOOL); }
    static LogicalType HASH() { return LogicalType(LogicalTypeID::UINT64); }
    static LogicalType INT64() { return LogicalType(LogicalTypeID::INT64); }
    static LogicalType INT32() { return LogicalType(LogicalTypeID::INT32); }
    static LogicalType INT16() { return LogicalType(LogicalTypeID::INT16); }
    static LogicalType INT8() { return LogicalType(LogicalTypeID::INT8); }
    static LogicalType UINT64() { return LogicalType(LogicalTypeID::UINT64); }
    static LogicalType UINT32() { return LogicalType(LogicalTypeID::UINT32); }
    static LogicalType UINT16() { return LogicalType(LogicalTypeID::UINT16); }
    static LogicalType UINT8() { return LogicalType(LogicalTypeID::UINT8); }
    static LogicalType INT128() { return LogicalType(LogicalTypeID::INT128); }
    static LogicalType DOUBLE() { return LogicalType(LogicalTypeID::DOUBLE); }
    static LogicalType FLOAT() { return LogicalType(LogicalTypeID::FLOAT); }
    static LogicalType DATE() { return LogicalType(LogicalTypeID::DATE); }
    static LogicalType TIMESTAMP_NS() { return LogicalType(LogicalTypeID::TIMESTAMP_NS); }
    static LogicalType TIMESTAMP_MS() { return LogicalType(LogicalTypeID::TIMESTAMP_MS); }
    static LogicalType TIMESTAMP_SEC() { return LogicalType(LogicalTypeID::TIMESTAMP_SEC); }
    static LogicalType TIMESTAMP_TZ() { return LogicalType(LogicalTypeID::TIMESTAMP_TZ); }
    static LogicalType TIMESTAMP() { return LogicalType(LogicalTypeID::TIMESTAMP); }
    static LogicalType INTERVAL() { return LogicalType(LogicalTypeID::INTERVAL); }
    static LBUG_API LogicalType DECIMAL(uint32_t precision, uint32_t scale);
    static LogicalType INTERNAL_ID() { return LogicalType(LogicalTypeID::INTERNAL_ID); }
    static LogicalType UINT128() { return LogicalType(LogicalTypeID::UINT128); };
    static LogicalType SERIAL() { return LogicalType(LogicalTypeID::SERIAL); }
    static LogicalType STRING() { return LogicalType(LogicalTypeID::STRING); }
    static LogicalType BLOB() { return LogicalType(LogicalTypeID::BLOB); }
    static LogicalType UUID() { return LogicalType(LogicalTypeID::UUID); }
    static LogicalType POINTER() { return LogicalType(LogicalTypeID::POINTER); }
    static LBUG_API LogicalType STRUCT(std::vector<StructField>&& fields);

    static LBUG_API LogicalType RECURSIVE_REL(std::vector<StructField>&& fields);

    static LBUG_API LogicalType NODE(std::vector<StructField>&& fields);

    static LBUG_API LogicalType REL(std::vector<StructField>&& fields);

    static LBUG_API LogicalType UNION(std::vector<StructField>&& fields);

    static LBUG_API LogicalType LIST(LogicalType childType);
    template<class T>
    static inline LogicalType LIST(T&& childType) {
        return LogicalType::LIST(LogicalType(std::forward<T>(childType)));
    }

    static LBUG_API LogicalType MAP(LogicalType keyType, LogicalType valueType);
    template<class T>
    static LogicalType MAP(T&& keyType, T&& valueType) {
        return LogicalType::MAP(LogicalType(std::forward<T>(keyType)),
            LogicalType(std::forward<T>(valueType)));
    }

    static LBUG_API LogicalType ARRAY(LogicalType childType, uint64_t numElements);
    template<class T>
    static LogicalType ARRAY(T&& childType, uint64_t numElements) {
        return LogicalType::ARRAY(LogicalType(std::forward<T>(childType)), numElements);
    }

private:
    friend struct CAPIHelper;
    friend struct JavaAPIHelper;
    friend class lbug::processor::ParquetReader;
    explicit LogicalType(LogicalTypeID typeID, std::unique_ptr<ExtraTypeInfo> extraTypeInfo);

private:
    LogicalTypeID typeID;
    PhysicalTypeID physicalType;
    std::unique_ptr<ExtraTypeInfo> extraTypeInfo;
    TypeCategory category = TypeCategory::INTERNAL;
};

class LBUG_API ExtraTypeInfo {
public:
    virtual ~ExtraTypeInfo() = default;

    void serialize(Serializer& serializer) const { serializeInternal(serializer); }

    virtual bool containsAny() const = 0;

    virtual bool operator==(const ExtraTypeInfo& other) const = 0;

    virtual std::unique_ptr<ExtraTypeInfo> copy() const = 0;

    template<class TARGET>
    const TARGET* constPtrCast() const {
        return common::ku_dynamic_cast<const TARGET*>(this);
    }

protected:
    virtual void serializeInternal(Serializer& serializer) const = 0;
};

class LBUG_API UDTTypeInfo : public ExtraTypeInfo {
public:
    explicit UDTTypeInfo(std::string typeName) : typeName{std::move(typeName)} {}

    std::string getTypeName() const { return typeName; }

    bool containsAny() const override { return false; }

    bool operator==(const ExtraTypeInfo& other) const override;

    std::unique_ptr<ExtraTypeInfo> copy() const override;

    static std::unique_ptr<ExtraTypeInfo> deserialize(Deserializer& deserializer);

private:
    void serializeInternal(Serializer& serializer) const override;

private:
    std::string typeName;
};

class DecimalTypeInfo final : public ExtraTypeInfo {
public:
    explicit DecimalTypeInfo(uint32_t precision = 18, uint32_t scale = 3)
        : precision(precision), scale(scale) {}

    uint32_t getPrecision() const { return precision; }
    uint32_t getScale() const { return scale; }

    bool containsAny() const override { return false; }

    bool operator==(const ExtraTypeInfo& other) const override;

    std::unique_ptr<ExtraTypeInfo> copy() const override;

    static std::unique_ptr<ExtraTypeInfo> deserialize(Deserializer& deserializer);

protected:
    void serializeInternal(Serializer& serializer) const override;

    uint32_t precision, scale;
};

class LBUG_API ListTypeInfo : public ExtraTypeInfo {
public:
    ListTypeInfo() = default;
    explicit ListTypeInfo(LogicalType childType) : childType{std::move(childType)} {}

    const LogicalType& getChildType() const { return childType; }

    bool containsAny() const override;

    bool operator==(const ExtraTypeInfo& other) const override;

    std::unique_ptr<ExtraTypeInfo> copy() const override;

    static std::unique_ptr<ExtraTypeInfo> deserialize(Deserializer& deserializer);

protected:
    void serializeInternal(Serializer& serializer) const override;

protected:
    LogicalType childType;
};

class LBUG_API ArrayTypeInfo final : public ListTypeInfo {
public:
    ArrayTypeInfo() : numElements{0} {};
    explicit ArrayTypeInfo(LogicalType childType, uint64_t numElements)
        : ListTypeInfo{std::move(childType)}, numElements{numElements} {}

    uint64_t getNumElements() const { return numElements; }

    bool operator==(const ExtraTypeInfo& other) const override;

    static std::unique_ptr<ExtraTypeInfo> deserialize(Deserializer& deserializer);

    std::unique_ptr<ExtraTypeInfo> copy() const override;

private:
    void serializeInternal(Serializer& serializer) const override;

private:
    uint64_t numElements;
};

class StructField {
public:
    StructField() : type{LogicalType()} {}
    StructField(std::string name, LogicalType type)
        : name{std::move(name)}, type{std::move(type)} {};

    DELETE_COPY_DEFAULT_MOVE(StructField);

    std::string getName() const { return name; }

    const LogicalType& getType() const { return type; }

    bool containsAny() const;

    bool operator==(const StructField& other) const;
    bool operator!=(const StructField& other) const { return !(*this == other); }

    void serialize(Serializer& serializer) const;

    static StructField deserialize(Deserializer& deserializer);

    StructField copy() const;

private:
    std::string name;
    LogicalType type;
};

class StructTypeInfo final : public ExtraTypeInfo {
public:
    StructTypeInfo() = default;
    explicit StructTypeInfo(std::vector<StructField>&& fields);
    StructTypeInfo(const std::vector<std::string>& fieldNames,
        const std::vector<LogicalType>& fieldTypes);

    bool hasField(const std::string& fieldName) const;
    struct_field_idx_t getStructFieldIdx(std::string fieldName) const;
    const StructField& getStructField(struct_field_idx_t idx) const;
    const StructField& getStructField(const std::string& fieldName) const;
    const std::vector<StructField>& getStructFields() const;

    const LogicalType& getChildType(struct_field_idx_t idx) const;
    std::vector<const LogicalType*> getChildrenTypes() const;
    // can't be a vector of refs since that can't be for-each looped through
    std::vector<std::string> getChildrenNames() const;

    bool containsAny() const override;

    bool operator==(const ExtraTypeInfo& other) const override;

    static std::unique_ptr<ExtraTypeInfo> deserialize(Deserializer& deserializer);
    std::unique_ptr<ExtraTypeInfo> copy() const override;

private:
    void serializeInternal(Serializer& serializer) const override;

private:
    std::vector<StructField> fields;
    std::unordered_map<std::string, struct_field_idx_t> fieldNameToIdxMap;
};

using logical_type_vec_t = std::vector<LogicalType>;

struct LBUG_API DecimalType {
    static uint32_t getPrecision(const LogicalType& type);
    static uint32_t getScale(const LogicalType& type);
    static std::string insertDecimalPoint(const std::string& value, uint32_t posFromEnd);
};

struct LBUG_API ListType {
    static const LogicalType& getChildType(const LogicalType& type);
};

struct LBUG_API ArrayType {
    static const LogicalType& getChildType(const LogicalType& type);
    static uint64_t getNumElements(const LogicalType& type);
};

struct LBUG_API StructType {
    static std::vector<const LogicalType*> getFieldTypes(const LogicalType& type);
    // since the field types isn't stored as a vector of LogicalTypes, we can't return vector<>&

    static const LogicalType& getFieldType(const LogicalType& type, struct_field_idx_t idx);

    static const LogicalType& getFieldType(const LogicalType& type, const std::string& key);

    static std::vector<std::string> getFieldNames(const LogicalType& type);

    static uint64_t getNumFields(const LogicalType& type);

    static const std::vector<StructField>& getFields(const LogicalType& type);

    static bool hasField(const LogicalType& type, const std::string& key);

    static const StructField& getField(const LogicalType& type, struct_field_idx_t idx);

    static const StructField& getField(const LogicalType& type, const std::string& key);

    static struct_field_idx_t getFieldIdx(const LogicalType& type, const std::string& key);
};

struct LBUG_API MapType {
    static const LogicalType& getKeyType(const LogicalType& type);

    static const LogicalType& getValueType(const LogicalType& type);
};

struct LBUG_API UnionType {
    static constexpr union_field_idx_t TAG_FIELD_IDX = 0;

    static constexpr auto TAG_FIELD_TYPE = LogicalTypeID::UINT16;

    static constexpr char TAG_FIELD_NAME[] = "tag";

    static union_field_idx_t getInternalFieldIdx(union_field_idx_t idx);

    static std::string getFieldName(const LogicalType& type, union_field_idx_t idx);

    static const LogicalType& getFieldType(const LogicalType& type, union_field_idx_t idx);

    static const LogicalType& getFieldType(const LogicalType& type, const std::string& key);

    static uint64_t getNumFields(const LogicalType& type);

    static bool hasField(const LogicalType& type, const std::string& key);

    static union_field_idx_t getFieldIdx(const LogicalType& type, const std::string& key);
};

struct PhysicalTypeUtils {
    static std::string toString(PhysicalTypeID physicalType);
    static uint32_t getFixedTypeSize(PhysicalTypeID physicalType);
};

struct LBUG_API LogicalTypeUtils {
    static std::string toString(LogicalTypeID dataTypeID);
    static std::string toString(const std::vector<LogicalType>& dataTypes);
    static std::string toString(const std::vector<LogicalTypeID>& dataTypeIDs);
    static uint32_t getRowLayoutSize(const LogicalType& logicalType);
    static bool isDate(const LogicalType& dataType);
    static bool isDate(const LogicalTypeID& dataType);
    static bool isTimestamp(const LogicalType& dataType);
    static bool isTimestamp(const LogicalTypeID& dataType);
    static bool isUnsigned(const LogicalType& dataType);
    static bool isUnsigned(const LogicalTypeID& dataType);
    static bool isIntegral(const LogicalType& dataType);
    static bool isIntegral(const LogicalTypeID& dataType);
    static bool isNumerical(const LogicalType& dataType);
    static bool isNumerical(const LogicalTypeID& dataType);
    static bool isFloatingPoint(const LogicalTypeID& dataType);
    static bool isNested(const LogicalType& dataType);
    static bool isNested(LogicalTypeID logicalTypeID);
    static std::vector<LogicalTypeID> getAllValidComparableLogicalTypes();
    static std::vector<LogicalTypeID> getNumericalLogicalTypeIDs();
    static std::vector<LogicalTypeID> getIntegerTypeIDs();
    static std::vector<LogicalTypeID> getFloatingPointTypeIDs();
    static std::vector<LogicalTypeID> getAllValidLogicTypeIDs();
    static std::vector<LogicalType> getAllValidLogicTypes();
    static bool tryGetMaxLogicalType(const LogicalType& left, const LogicalType& right,
        LogicalType& result);
    static bool tryGetMaxLogicalType(const std::vector<LogicalType>& types, LogicalType& result);

    // Differs from tryGetMaxLogicalType because it treats string as a maximal type, instead of a
    // minimal type. as such, it will always succeed.
    // Also combines structs by the union of their fields. As such, currently, it is not guaranteed
    // for casting to work from input types to resulting types. Ideally this changes
    static LogicalType combineTypes(const LogicalType& left, const LogicalType& right);
    static LogicalType combineTypes(const std::vector<LogicalType>& types);

    // makes a copy of the type with any occurences of ANY replaced with replacement
    static LogicalType purgeAny(const LogicalType& type, const LogicalType& replacement);

private:
    static bool tryGetMaxLogicalTypeID(const LogicalTypeID& left, const LogicalTypeID& right,
        LogicalTypeID& result);
};

enum class FileVersionType : uint8_t { ORIGINAL = 0, WAL_VERSION = 1 };

} // namespace common
} // namespace lbug
