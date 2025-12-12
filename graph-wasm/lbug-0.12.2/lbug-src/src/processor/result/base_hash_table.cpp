#include "processor/result/base_hash_table.h"

#include "math.h"

#include "common/constants.h"
#include "common/null_buffer.h"
#include "common/type_utils.h"
#include "common/types/ku_list.h"
#include "common/types/types.h"
#include "common/utils.h"
#include "function/comparison/comparison_functions.h"
#include "function/hash/vector_hash_functions.h"

using namespace lbug::common;
using namespace lbug::function;

namespace lbug {
namespace processor {

BaseHashTable::BaseHashTable(storage::MemoryManager& memoryManager, logical_type_vec_t keyTypes)
    : maxNumHashSlots{0}, numSlotsPerBlockLog2{0}, slotIdxInBlockMask{0},
      memoryManager{&memoryManager}, keyTypes{std::move(keyTypes)} {
    initCompareFuncs();
    initTmpHashVector();
}

void BaseHashTable::setMaxNumHashSlots(uint64_t newSize) {
    maxNumHashSlots = newSize;
}

void BaseHashTable::computeVectorHashes(std::span<const ValueVector*> keyVectors) {
    hashVector->state = keyVectors[0]->state;
    VectorHashFunction::computeHash(*keyVectors[0], keyVectors[0]->state->getSelVector(),
        *hashVector.get(), hashVector->state->getSelVector());

    for (size_t startVecIdx = 1; startVecIdx < keyVectors.size(); startVecIdx++) {
        auto keyVector = keyVectors[startVecIdx];
        tmpHashResultVector->state = keyVector->state;
        tmpHashCombineResultVector->state = keyVector->state;
        VectorHashFunction::computeHash(*keyVector, keyVector->state->getSelVector(),
            *tmpHashResultVector, tmpHashResultVector->state->getSelVector());
        tmpHashCombineResultVector->state =
            !tmpHashResultVector->state->isFlat() ? tmpHashResultVector->state : hashVector->state;
        VectorHashFunction::combineHash(*hashVector, hashVector->state->getSelVector(),
            *tmpHashResultVector, tmpHashResultVector->state->getSelVector(),
            *tmpHashCombineResultVector, tmpHashCombineResultVector->state->getSelVector());
        hashVector.swap(tmpHashCombineResultVector);
    }
}

template<typename T>
static bool compareEntry(const common::ValueVector* vector, uint32_t vectorPos,
    const uint8_t* entry) {
    uint8_t result = 0;
    auto key = vector->getData() + vectorPos * vector->getNumBytesPerValue();
    function::Equals::operation(*(T*)key, *(T*)entry, result, nullptr /* leftVector */,
        nullptr /* rightVector */);
    return result != 0;
}

template<typename T>
static bool factorizedTableCompareEntry(const uint8_t* entry1, const uint8_t* entry2,
    const LogicalType&) {
    return function::Equals::operation(*(T*)entry1, *(T*)entry2);
}

static ft_compare_function_t getFactorizedTableCompareEntryFunc(const LogicalType& type);

template<>
bool factorizedTableCompareEntry<list_entry_t>(const uint8_t* entry1, const uint8_t* entry2,
    const LogicalType& type) {
    const auto* list1 = reinterpret_cast<const ku_list_t*>(entry1);
    const auto* list2 = reinterpret_cast<const ku_list_t*>(entry2);
    if (list1->size != list2->size) {
        return false;
    }
    const auto& childType = ListType::getChildType(type);
    const auto childSize = LogicalTypeUtils::getRowLayoutSize(childType);
    const auto nullPtr1 = reinterpret_cast<const uint8_t*>(list1->overflowPtr);
    const auto nullPtr2 = reinterpret_cast<const uint8_t*>(list2->overflowPtr);
    const auto dataPtr1 = nullPtr1 + NullBuffer::getNumBytesForNullValues(list1->size);
    const auto dataPtr2 = nullPtr2 + NullBuffer::getNumBytesForNullValues(list2->size);
    auto compareFunc = getFactorizedTableCompareEntryFunc(childType);
    for (size_t index = 0; index < list1->size; index++) {
        const bool child1IsNull = NullBuffer::isNull(nullPtr1, index);
        const bool child2IsNull = NullBuffer::isNull(nullPtr2, index);
        if (child1IsNull != child2IsNull) {
            return false;
        }
        if (!child1IsNull && !child2IsNull &&
            !compareFunc(dataPtr1 + index * childSize, dataPtr2 + index * childSize, childType)) {
            return false;
        }
    }
    return true;
}

const uint8_t* getFTStructFirstField(const uint8_t* structEntry, uint64_t numFields) {
    return structEntry + common::NullBuffer::getNumBytesForNullValues(numFields);
}

const uint8_t* getFTStructNodeID(const uint8_t* structEntry, const LogicalType& type) {
    return getFTStructFirstField(structEntry, common::StructType::getNumFields(type));
}

const uint8_t* getFTStructRelID(const uint8_t* structEntry, const LogicalType& type) {
    return getFTStructFirstField(structEntry, common::StructType::getNumFields(type)) +
           sizeof(common::internalID_t) * 2 + sizeof(common::ku_string_t);
}

static bool compareFTNodeEntry(const uint8_t* entry1, const uint8_t* entry2,
    const LogicalType& type) {
    return factorizedTableCompareEntry<common::internalID_t>(getFTStructNodeID(entry1, type),
        getFTStructNodeID(entry2, type),
        type /*not actually used; should really be the type of the field*/);
}

static bool compareFTRelEntry(const uint8_t* entry1, const uint8_t* entry2,
    const LogicalType& type) {
    return factorizedTableCompareEntry<common::internalID_t>(getFTStructRelID(entry1, type),
        getFTStructRelID(entry2, type),
        type /*not actually used; should really be the type of the field*/);
}

template<>
bool factorizedTableCompareEntry<struct_entry_t>(const uint8_t* entry1, const uint8_t* entry2,
    const LogicalType& type) {
    const auto numFields = StructType::getNumFields(type);
    auto entryToCompare1 = getFTStructFirstField(entry1, numFields);
    auto entryToCompare2 = getFTStructFirstField(entry2, numFields);
    for (auto i = 0u; i < numFields; i++) {
        const auto isNullInEntry1 = NullBuffer::isNull(entry1, i);
        const auto isNullInEntry2 = NullBuffer::isNull(entry2, i);
        if (isNullInEntry1 != isNullInEntry2) {
            return false;
        }
        const auto& fieldType = StructType::getFieldType(type, i);
        ft_compare_function_t compareFunc = getFactorizedTableCompareEntryFunc(fieldType);
        // If both not null, compare the value.
        if (!isNullInEntry1 && !compareFunc(entryToCompare1, entryToCompare2, fieldType)) {
            return false;
        }
        const auto fieldSize = LogicalTypeUtils::getRowLayoutSize(fieldType);
        entryToCompare1 += fieldSize;
        entryToCompare2 += fieldSize;
    }
    return true;
}

static compare_function_t getCompareEntryFunc(const LogicalType& type);

template<>
[[maybe_unused]] bool compareEntry<list_entry_t>(const common::ValueVector* vector,
    uint32_t vectorPos, const uint8_t* entry) {
    auto dataVector = ListVector::getDataVector(vector);
    auto listToCompare = vector->getValue<list_entry_t>(vectorPos);
    auto listEntry = reinterpret_cast<const ku_list_t*>(entry);
    auto entryNullBytes = reinterpret_cast<uint8_t*>(listEntry->overflowPtr);
    auto entryValues = entryNullBytes + NullBuffer::getNumBytesForNullValues(listEntry->size);
    auto rowLayoutSize = LogicalTypeUtils::getRowLayoutSize(dataVector->dataType);
    compare_function_t compareFunc = getCompareEntryFunc(dataVector->dataType);
    if (listToCompare.size != listEntry->size) {
        return false;
    }
    for (auto i = 0u; i < listEntry->size; i++) {
        const bool entryChildIsNull = NullBuffer::isNull(entryNullBytes, i);
        const bool vectorChildIsNull = dataVector->isNull(listToCompare.offset + i);
        if (entryChildIsNull != vectorChildIsNull) {
            return false;
        }
        if (!entryChildIsNull && !vectorChildIsNull &&
            !compareFunc(dataVector, listToCompare.offset + i, entryValues)) {
            return false;
        }
        entryValues += rowLayoutSize;
    }
    return true;
}

static bool compareNodeEntry(const common::ValueVector* vector, uint32_t vectorPos,
    const uint8_t* entry) {
    KU_ASSERT(0 == common::StructType::getFieldIdx(vector->dataType, common::InternalKeyword::ID));
    auto idVector = common::StructVector::getFieldVector(vector, 0).get();
    return compareEntry<common::internalID_t>(idVector, vectorPos,
        getFTStructNodeID(entry, vector->dataType));
}

static bool compareRelEntry(const common::ValueVector* vector, uint32_t vectorPos,
    const uint8_t* entry) {
    KU_ASSERT(3 == common::StructType::getFieldIdx(vector->dataType, common::InternalKeyword::ID));
    auto idVector = common::StructVector::getFieldVector(vector, 3).get();
    return compareEntry<common::internalID_t>(idVector, vectorPos,
        getFTStructRelID(entry, vector->dataType));
}

template<>
[[maybe_unused]] bool compareEntry<struct_entry_t>(const common::ValueVector* vector,
    uint32_t vectorPos, const uint8_t* entry) {
    auto numFields = StructType::getNumFields(vector->dataType);
    auto entryToCompare = getFTStructFirstField(entry, numFields);
    for (auto i = 0u; i < numFields; i++) {
        auto isNullInEntry = NullBuffer::isNull(entry, i);
        auto fieldVector = StructVector::getFieldVector(vector, i);
        // Firstly check null on left and right side.
        if (isNullInEntry != fieldVector->isNull(vectorPos)) {
            return false;
        }
        compare_function_t compareFunc = getCompareEntryFunc(fieldVector->dataType);
        // If both not null, compare the value.
        if (!isNullInEntry && !compareFunc(fieldVector.get(), vectorPos, entryToCompare)) {
            return false;
        }
        entryToCompare += LogicalTypeUtils::getRowLayoutSize(fieldVector->dataType);
    }
    return true;
}

static compare_function_t getCompareEntryFunc(const LogicalType& type) {
    compare_function_t func;
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::NODE: {
        func = compareNodeEntry;
    } break;
    case LogicalTypeID::REL: {
        func = compareRelEntry;
    } break;
    default: {
        TypeUtils::visit(
            type.getPhysicalType(), [&]<HashableTypes T>(T) { func = compareEntry<T>; },
            [](auto) { KU_UNREACHABLE; });
    }
    }
    return func;
}

static ft_compare_function_t getFactorizedTableCompareEntryFunc(const LogicalType& type) {
    ft_compare_function_t func;
    switch (type.getLogicalTypeID()) {
    case LogicalTypeID::NODE: {
        func = compareFTNodeEntry;
    } break;
    case LogicalTypeID::REL: {
        func = compareFTRelEntry;
    } break;
    default: {
        TypeUtils::visit(
            type.getPhysicalType(),
            [&]<HashableTypes T>(T) { func = factorizedTableCompareEntry<T>; },
            [](auto) { KU_UNREACHABLE; });
    }
    }
    return func;
}

void BaseHashTable::initSlotConstant(uint64_t numSlotsPerBlock) {
    numSlotsPerBlockLog2 = std::log2(numSlotsPerBlock);
    slotIdxInBlockMask =
        common::BitmaskUtils::all1sMaskForLeastSignificantBits<uint64_t>(numSlotsPerBlockLog2);
}

// ! This function will only be used by distinct aggregate and hashJoin, which assumes that all
// keyVectors are flat.
bool BaseHashTable::matchFlatVecWithEntry(const std::vector<common::ValueVector*>& keyVectors,
    const uint8_t* entry) {
    for (auto i = 0u; i < keyVectors.size(); i++) {
        auto keyVector = keyVectors[i];
        KU_ASSERT(keyVector->state->isFlat());
        KU_ASSERT(keyVector->state->getSelVector().getSelSize() == 1);
        auto pos = keyVector->state->getSelVector()[0];
        auto isKeyVectorNull = keyVector->isNull(pos);
        auto isEntryKeyNull =
            factorizedTable->isNonOverflowColNull(entry + getTableSchema()->getNullMapOffset(), i);
        // If either key or entry is null, we shouldn't compare the value of keyVector and
        // entry.
        if (isKeyVectorNull && isEntryKeyNull) {
            continue;
        } else if (isKeyVectorNull != isEntryKeyNull) {
            return false;
        }
        if (!compareEntryFuncs[i](keyVector, pos, entry + getTableSchema()->getColOffset(i))) {
            return false;
        }
    }
    return true;
}

void BaseHashTable::initCompareFuncs() {
    compareEntryFuncs.reserve(keyTypes.size());
    for (auto i = 0u; i < keyTypes.size(); ++i) {
        compareEntryFuncs.push_back(getCompareEntryFunc(keyTypes[i]));
        ftCompareEntryFuncs.push_back(getFactorizedTableCompareEntryFunc(keyTypes[i]));
    }
}

void BaseHashTable::initTmpHashVector() {
    hashState = std::make_shared<DataChunkState>();
    hashState->setToFlat();
    hashVector = std::make_unique<ValueVector>(LogicalType::HASH(), memoryManager);
    hashVector->state = hashState;
    tmpHashResultVector = std::make_unique<ValueVector>(LogicalType::HASH(), memoryManager);
    tmpHashCombineResultVector = std::make_unique<ValueVector>(LogicalType::HASH(), memoryManager);
}

} // namespace processor
} // namespace lbug
