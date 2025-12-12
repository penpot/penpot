#include "function/comparison/comparison_functions.h"

#include "common/types/int128_t.h"
#include "common/types/interval_t.h"

using namespace lbug::common;

namespace lbug {
namespace function {

template<typename OP>
static void executeNestedOperation(uint8_t& result, ValueVector* leftVector,
    ValueVector* rightVector, uint64_t leftPos, uint64_t rightPos) {
    switch (leftVector->dataType.getPhysicalType()) {
    case PhysicalTypeID::BOOL: {
        OP::operation(leftVector->getValue<uint8_t>(leftPos),
            rightVector->getValue<uint8_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INT64: {
        OP::operation(leftVector->getValue<int64_t>(leftPos),
            rightVector->getValue<int64_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INT32: {
        OP::operation(leftVector->getValue<int32_t>(leftPos),
            rightVector->getValue<int32_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INT16: {
        OP::operation(leftVector->getValue<int16_t>(leftPos),
            rightVector->getValue<int16_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INT8: {
        OP::operation(leftVector->getValue<int8_t>(leftPos),
            rightVector->getValue<int8_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::UINT64: {
        OP::operation(leftVector->getValue<uint64_t>(leftPos),
            rightVector->getValue<uint64_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::UINT32: {
        OP::operation(leftVector->getValue<uint32_t>(leftPos),
            rightVector->getValue<uint32_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::UINT16: {
        OP::operation(leftVector->getValue<uint16_t>(leftPos),
            rightVector->getValue<uint16_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::UINT8: {
        OP::operation(leftVector->getValue<uint8_t>(leftPos),
            rightVector->getValue<uint8_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INT128: {
        OP::operation(leftVector->getValue<int128_t>(leftPos),
            rightVector->getValue<int128_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::DOUBLE: {
        OP::operation(leftVector->getValue<double>(leftPos),
            rightVector->getValue<double>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::FLOAT: {
        OP::operation(leftVector->getValue<float>(leftPos), rightVector->getValue<float>(rightPos),
            result, nullptr /* left */, nullptr /* right */);
    } break;
    case PhysicalTypeID::STRING: {
        OP::operation(leftVector->getValue<ku_string_t>(leftPos),
            rightVector->getValue<ku_string_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INTERVAL: {
        OP::operation(leftVector->getValue<interval_t>(leftPos),
            rightVector->getValue<interval_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::INTERNAL_ID: {
        OP::operation(leftVector->getValue<internalID_t>(leftPos),
            rightVector->getValue<internalID_t>(rightPos), result, nullptr /* left */,
            nullptr /* right */);
    } break;
    case PhysicalTypeID::ARRAY:
    case PhysicalTypeID::LIST: {
        OP::operation(leftVector->getValue<list_entry_t>(leftPos),
            rightVector->getValue<list_entry_t>(rightPos), result, leftVector, rightVector);
    } break;
    case PhysicalTypeID::STRUCT: {
        OP::operation(leftVector->getValue<struct_entry_t>(leftPos),
            rightVector->getValue<struct_entry_t>(rightPos), result, leftVector, rightVector);
    } break;
    default: {
        KU_UNREACHABLE;
    }
    }
}

static void executeNestedEqual(uint8_t& result, ValueVector* leftVector, ValueVector* rightVector,
    uint64_t leftPos, uint64_t rightPos) {
    if (leftVector->isNull(leftPos) && rightVector->isNull(rightPos)) {
        result = true;
    } else if (leftVector->isNull(leftPos) != rightVector->isNull(rightPos)) {
        result = false;
    } else {
        executeNestedOperation<Equals>(result, leftVector, rightVector, leftPos, rightPos);
    }
}

template<>
void Equals::operation(const list_entry_t& left, const list_entry_t& right, uint8_t& result,
    ValueVector* leftVector, ValueVector* rightVector) {
    if (leftVector->dataType != rightVector->dataType || left.size != right.size) {
        result = false;
        return;
    }
    auto leftDataVector = ListVector::getDataVector(leftVector);
    auto rightDataVector = ListVector::getDataVector(rightVector);
    for (auto i = 0u; i < left.size; i++) {
        auto leftPos = left.offset + i;
        auto rightPos = right.offset + i;
        executeNestedEqual(result, leftDataVector, rightDataVector, leftPos, rightPos);
        if (!result) {
            return;
        }
    }
    result = true;
}

template<>
void Equals::operation(const struct_entry_t& left, const struct_entry_t& right, uint8_t& result,
    ValueVector* leftVector, ValueVector* rightVector) {
    if (leftVector->dataType != rightVector->dataType) {
        result = false;
        return;
    }
    auto leftFields = StructVector::getFieldVectors(leftVector);
    auto rightFields = StructVector::getFieldVectors(rightVector);
    for (auto i = 0u; i < leftFields.size(); i++) {
        auto leftField = leftFields[i].get();
        auto rightField = rightFields[i].get();
        executeNestedEqual(result, leftField, rightField, left.pos, right.pos);
        if (!result) {
            return;
        }
    }
    result = true;
    // For STRUCT type, we also need to check their field names
    if (result || leftVector->dataType.getLogicalTypeID() == LogicalTypeID::STRUCT ||
        rightVector->dataType.getLogicalTypeID() == LogicalTypeID::STRUCT) {
        auto leftTypeNames = StructType::getFieldNames(leftVector->dataType);
        auto rightTypeNames = StructType::getFieldNames(rightVector->dataType);
        for (auto i = 0u; i < leftTypeNames.size(); i++) {
            if (leftTypeNames[i] != rightTypeNames[i]) {
                result = false;
            }
        }
    }
}

static void executeNestedGreaterThan(uint8_t& isGreaterThan, uint8_t& isEqual,
    ValueVector* leftDataVector, ValueVector* rightDataVector, uint64_t leftPos,
    uint64_t rightPos) {
    auto isLeftNull = leftDataVector->isNull(leftPos);
    auto isRightNull = rightDataVector->isNull(rightPos);
    if (isLeftNull || isRightNull) {
        isGreaterThan = !isRightNull;
        isEqual = (isLeftNull == isRightNull);
    } else {
        executeNestedOperation<GreaterThan>(isGreaterThan, leftDataVector, rightDataVector, leftPos,
            rightPos);
        if (!isGreaterThan) {
            executeNestedOperation<Equals>(isEqual, leftDataVector, rightDataVector, leftPos,
                rightPos);
        } else {
            isEqual = false;
        }
    }
}

template<>
void GreaterThan::operation(const list_entry_t& left, const list_entry_t& right, uint8_t& result,
    ValueVector* leftVector, ValueVector* rightVector) {
    KU_ASSERT(leftVector->dataType == rightVector->dataType);
    auto leftDataVector = ListVector::getDataVector(leftVector);
    auto rightDataVector = ListVector::getDataVector(rightVector);
    auto commonLength = std::min(left.size, right.size);
    uint8_t isEqual = 0;
    for (auto i = 0u; i < commonLength; i++) {
        auto leftPos = left.offset + i;
        auto rightPos = right.offset + i;
        executeNestedGreaterThan(result, isEqual, leftDataVector, rightDataVector, leftPos,
            rightPos);
        if (result || (!result && !isEqual)) {
            return;
        }
    }
    result = left.size > right.size;
}

template<>
void GreaterThan::operation(const struct_entry_t& left, const struct_entry_t& right,
    uint8_t& result, ValueVector* leftVector, ValueVector* rightVector) {
    KU_ASSERT(leftVector->dataType == rightVector->dataType);
    auto leftFields = StructVector::getFieldVectors(leftVector);
    auto rightFields = StructVector::getFieldVectors(rightVector);
    uint8_t isEqual = 0;
    for (auto i = 0u; i < leftFields.size(); i++) {
        auto leftField = leftFields[i].get();
        auto rightField = rightFields[i].get();
        executeNestedGreaterThan(result, isEqual, leftField, rightField, left.pos, right.pos);
        if (result || (!result && !isEqual)) {
            return;
        }
    }
    result = false;
}

} // namespace function
} // namespace lbug
