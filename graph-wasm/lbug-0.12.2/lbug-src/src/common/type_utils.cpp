#include "common/type_utils.h"

#include "common/exception/runtime.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {

std::string TypeUtils::entryToString(const LogicalType& dataType, const uint8_t* value,
    ValueVector* vector) {
    auto valueVector = reinterpret_cast<ValueVector*>(vector);
    switch (dataType.getLogicalTypeID()) {
    case LogicalTypeID::BOOL:
        return TypeUtils::toString(*reinterpret_cast<const bool*>(value));
    case LogicalTypeID::SERIAL:
    case LogicalTypeID::INT64:
        return TypeUtils::toString(*reinterpret_cast<const int64_t*>(value));
    case LogicalTypeID::INT32:
        return TypeUtils::toString(*reinterpret_cast<const int32_t*>(value));
    case LogicalTypeID::INT16:
        return TypeUtils::toString(*reinterpret_cast<const int16_t*>(value));
    case LogicalTypeID::INT8:
        return TypeUtils::toString(*reinterpret_cast<const int8_t*>(value));
    case LogicalTypeID::UINT64:
        return TypeUtils::toString(*reinterpret_cast<const uint64_t*>(value));
    case LogicalTypeID::UINT32:
        return TypeUtils::toString(*reinterpret_cast<const uint32_t*>(value));
    case LogicalTypeID::UINT16:
        return TypeUtils::toString(*reinterpret_cast<const uint16_t*>(value));
    case LogicalTypeID::UINT8:
        return TypeUtils::toString(*reinterpret_cast<const uint8_t*>(value));
    case LogicalTypeID::INT128:
        return TypeUtils::toString(*reinterpret_cast<const int128_t*>(value));
    case LogicalTypeID::DOUBLE:
        return TypeUtils::toString(*reinterpret_cast<const double*>(value));
    case LogicalTypeID::FLOAT:
        return TypeUtils::toString(*reinterpret_cast<const float*>(value));
    case LogicalTypeID::DECIMAL:
        switch (dataType.getPhysicalType()) {
        case PhysicalTypeID::INT16:
            return DecimalType::insertDecimalPoint(
                TypeUtils::toString(*reinterpret_cast<const int16_t*>(value)),
                DecimalType::getScale(dataType));
        case PhysicalTypeID::INT32:
            return DecimalType::insertDecimalPoint(
                TypeUtils::toString(*reinterpret_cast<const int32_t*>(value)),
                DecimalType::getScale(dataType));
        case PhysicalTypeID::INT64:
            return DecimalType::insertDecimalPoint(
                TypeUtils::toString(*reinterpret_cast<const int64_t*>(value)),
                DecimalType::getScale(dataType));
        case PhysicalTypeID::INT128:
            return DecimalType::insertDecimalPoint(
                TypeUtils::toString(*reinterpret_cast<const int128_t*>(value)),
                DecimalType::getScale(dataType));
        default:
            // decimals should always be backed by one of these four
            KU_UNREACHABLE;
        }
    case LogicalTypeID::DATE:
        return TypeUtils::toString(*reinterpret_cast<const date_t*>(value));
    case LogicalTypeID::TIMESTAMP_NS:
        return TypeUtils::toString(*reinterpret_cast<const timestamp_ns_t*>(value));
    case LogicalTypeID::TIMESTAMP_MS:
        return TypeUtils::toString(*reinterpret_cast<const timestamp_ms_t*>(value));
    case LogicalTypeID::TIMESTAMP_SEC:
        return TypeUtils::toString(*reinterpret_cast<const timestamp_sec_t*>(value));
    case LogicalTypeID::TIMESTAMP_TZ:
        return TypeUtils::toString(*reinterpret_cast<const timestamp_tz_t*>(value));
    case LogicalTypeID::TIMESTAMP:
        return TypeUtils::toString(*reinterpret_cast<const timestamp_t*>(value));
    case LogicalTypeID::INTERVAL:
        return TypeUtils::toString(*reinterpret_cast<const interval_t*>(value));
    case LogicalTypeID::BLOB:
        return TypeUtils::toString(*reinterpret_cast<const blob_t*>(value));
    case LogicalTypeID::STRING:
        return TypeUtils::toString(*reinterpret_cast<const ku_string_t*>(value));
    case LogicalTypeID::INTERNAL_ID:
        return TypeUtils::toString(*reinterpret_cast<const internalID_t*>(value));
    case LogicalTypeID::UINT128:
        return TypeUtils::toString(*reinterpret_cast<const uint128_t*>(value));
    case LogicalTypeID::ARRAY:
    case LogicalTypeID::LIST:
        return TypeUtils::toString(*reinterpret_cast<const list_entry_t*>(value), valueVector);
    case LogicalTypeID::MAP:
        return TypeUtils::toString(*reinterpret_cast<const map_entry_t*>(value), valueVector);
    case LogicalTypeID::STRUCT:
        return TypeUtils::toString(*reinterpret_cast<const struct_entry_t*>(value), valueVector);
    case LogicalTypeID::UNION:
        return TypeUtils::toString(*reinterpret_cast<const union_entry_t*>(value), valueVector);
    case LogicalTypeID::UUID:
        return TypeUtils::toString(*reinterpret_cast<const ku_uuid_t*>(value));
    case LogicalTypeID::NODE:
        return TypeUtils::nodeToString(*reinterpret_cast<const struct_entry_t*>(value),
            valueVector);
    case LogicalTypeID::REL:
        return TypeUtils::relToString(*reinterpret_cast<const struct_entry_t*>(value), valueVector);
    default:
        throw common::RuntimeException{
            common::stringFormat("Unsupported type: {} to string.", dataType.toString())};
    }
}

static std::string entryToStringWithPos(sel_t pos, ValueVector* vector) {
    if (vector->isNull(pos)) {
        return "";
    }
    return TypeUtils::entryToString(vector->dataType,
        vector->getData() + vector->getNumBytesPerValue() * pos, vector);
}

template<>
std::string TypeUtils::toString(const int128_t& val, void* /*valueVector*/) {
    return Int128_t::toString(val);
}

template<>
std::string TypeUtils::toString(const uint128_t& val, void* /*valueVector*/) {
    return UInt128_t::toString(val);
}

template<>
std::string TypeUtils::toString(const bool& val, void* /*valueVector*/) {
    return val ? "True" : "False";
}

template<>
std::string TypeUtils::toString(const internalID_t& val, void* /*valueVector*/) {
    return std::to_string(val.tableID) + ":" + std::to_string(val.offset);
}

template<>
std::string TypeUtils::toString(const date_t& val, void* /*valueVector*/) {
    return Date::toString(val);
}

template<>
std::string TypeUtils::toString(const timestamp_ns_t& val, void* /*valueVector*/) {
    return toString(Timestamp::fromEpochNanoSeconds(val.value));
}

template<>
std::string TypeUtils::toString(const timestamp_ms_t& val, void* /*valueVector*/) {
    return toString(Timestamp::fromEpochMilliSeconds(val.value));
}

template<>
std::string TypeUtils::toString(const timestamp_sec_t& val, void* /*valueVector*/) {
    return toString(Timestamp::fromEpochSeconds(val.value));
}

template<>
std::string TypeUtils::toString(const timestamp_tz_t& val, void* /*valueVector*/) {
    return toString(static_cast<timestamp_t>(val)) + "+00";
}

template<>
std::string TypeUtils::toString(const timestamp_t& val, void* /*valueVector*/) {
    return Timestamp::toString(val);
}

template<>
std::string TypeUtils::toString(const interval_t& val, void* /*valueVector*/) {
    return Interval::toString(val);
}

template<>
std::string TypeUtils::toString(const ku_string_t& val, void* /*valueVector*/) {
    return val.getAsString();
}

template<>
std::string TypeUtils::toString(const blob_t& val, void* /*valueVector*/) {
    return Blob::toString(val.value.getData(), val.value.len);
}

template<>
std::string TypeUtils::toString(const ku_uuid_t& val, void* /*valueVector*/) {
    return UUID::toString(val);
}

template<>
std::string TypeUtils::toString(const list_entry_t& val, void* valueVector) {
    auto listVector = (ValueVector*)valueVector;
    if (val.size == 0) {
        return "[]";
    }
    std::string result = "[";
    auto dataVector = ListVector::getDataVector(listVector);
    for (auto i = 0u; i < val.size - 1; ++i) {
        result += entryToStringWithPos(val.offset + i, dataVector);
        result += ",";
    }
    result += entryToStringWithPos(val.offset + val.size - 1, dataVector);
    result += "]";
    return result;
}

static std::string getMapEntryStr(sel_t pos, ValueVector* dataVector, ValueVector* keyVector,
    ValueVector* valVector) {
    if (dataVector->isNull(pos)) {
        return "";
    }
    return entryToStringWithPos(pos, keyVector) + "=" + entryToStringWithPos(pos, valVector);
}

template<>
std::string TypeUtils::toString(const map_entry_t& val, void* valueVector) {
    auto mapVector = (ValueVector*)valueVector;
    if (val.entry.size == 0) {
        return "{}";
    }
    std::string result = "{";
    auto dataVector = ListVector::getDataVector(mapVector);
    auto keyVector = MapVector::getKeyVector(mapVector);
    auto valVector = MapVector::getValueVector(mapVector);
    for (auto i = 0u; i < val.entry.size - 1; ++i) {
        auto pos = val.entry.offset + i;
        result += getMapEntryStr(pos, dataVector, keyVector, valVector);
        result += ", ";
    }
    auto pos = val.entry.offset + val.entry.size - 1;
    result += getMapEntryStr(pos, dataVector, keyVector, valVector);
    result += "}";
    return result;
}

template<bool SKIP_NULL_ENTRY>
static std::string structToString(const struct_entry_t& val, ValueVector* vector) {
    const auto& fields = StructType::getFields(vector->dataType);
    if (fields.size() == 0) {
        return "{}";
    }
    std::string result = "{";
    auto i = 0u;
    for (; i < fields.size() - 1; ++i) {
        auto fieldVector = StructVector::getFieldVector(vector, i);
        if constexpr (SKIP_NULL_ENTRY) {
            if (fieldVector->isNull(val.pos)) {
                continue;
            }
        }
        if (i != 0) {
            result += ", ";
        }
        result += StructType::getField(vector->dataType, i).getName();
        result += ": ";
        result += entryToStringWithPos(val.pos, fieldVector.get());
    }
    auto fieldVector = StructVector::getFieldVector(vector, i);
    if constexpr (SKIP_NULL_ENTRY) {
        if (fieldVector->isNull(val.pos)) {
            result += "}";
            return result;
        }
    }
    if (i != 0) {
        result += ", ";
    }
    result += StructType::getField(vector->dataType, i).getName();
    result += ": ";
    result += entryToStringWithPos(val.pos, fieldVector.get());
    result += "}";
    return result;
}

std::string TypeUtils::nodeToString(const struct_entry_t& val, ValueVector* vector) {
    // Internal ID vector is the first field vector.
    if (StructVector::getFieldVector(vector, 0)->isNull(val.pos)) {
        return "";
    }
    return structToString<true>(val, vector);
}

std::string TypeUtils::relToString(const struct_entry_t& val, ValueVector* vector) {
    // Internal ID vector is the third field vector.
    if (StructVector::getFieldVector(vector, 3)->isNull(val.pos)) {
        return "";
    }
    return structToString<true>(val, vector);
}

template<>
std::string TypeUtils::toString(const struct_entry_t& val, void* valVector) {
    return structToString<false>(val, (ValueVector*)valVector);
}

template<>
std::string TypeUtils::toString(const union_entry_t& val, void* valVector) {
    auto structVector = (ValueVector*)valVector;
    auto unionFieldIdx =
        UnionVector::getTagVector(structVector)->getValue<union_field_idx_t>(val.entry.pos);
    auto unionFieldVector = UnionVector::getValVector(structVector, unionFieldIdx);
    return entryToStringWithPos(val.entry.pos, unionFieldVector);
}

} // namespace common
} // namespace lbug
