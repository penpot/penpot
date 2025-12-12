#pragma once

#include <optional>
#include <utility>

#include "common/assert.h"
#include "common/cast.h"
#include "common/copy_constructors.h"
#include "common/data_chunk/data_chunk_state.h"
#include "common/null_mask.h"
#include "common/types/ku_string.h"
#include "common/vector/auxiliary_buffer.h"

namespace lbug {
namespace common {

class Value;

//! A Vector represents values of the same data type.
//! The capacity of a ValueVector is either 1 (sequence) or DEFAULT_VECTOR_CAPACITY.
class LBUG_API ValueVector {
    friend class ListVector;
    friend class ListAuxiliaryBuffer;
    friend class StructVector;
    friend class StringVector;
    friend class ArrowColumnVector;

public:
    explicit ValueVector(LogicalType dataType, storage::MemoryManager* memoryManager = nullptr,
        std::shared_ptr<DataChunkState> dataChunkState = nullptr);
    explicit ValueVector(LogicalTypeID dataTypeID, storage::MemoryManager* memoryManager = nullptr)
        : ValueVector(LogicalType(dataTypeID), memoryManager) {
        KU_ASSERT(dataTypeID != LogicalTypeID::LIST);
    }

    DELETE_COPY_AND_MOVE(ValueVector);
    ~ValueVector() = default;

    template<typename T>
    std::optional<T> firstNonNull() const {
        sel_t selectedSize = state->getSelSize();
        if (selectedSize == 0) {
            return std::nullopt;
        }
        if (hasNoNullsGuarantee()) {
            return getValue<T>(state->getSelVector()[0]);
        } else {
            for (size_t i = 0; i < selectedSize; i++) {
                auto pos = state->getSelVector()[i];
                if (!isNull(pos)) {
                    return std::make_optional(getValue<T>(pos));
                }
            }
        }
        return std::nullopt;
    }

    template<class Func>
    void forEachNonNull(Func&& func) const {
        if (hasNoNullsGuarantee()) {
            state->getSelVector().forEach(func);
        } else {
            state->getSelVector().forEach([&](auto i) {
                if (!isNull(i)) {
                    func(i);
                }
            });
        }
    }

    uint32_t countNonNull() const;

    void setState(const std::shared_ptr<DataChunkState>& state_);

    void setAllNull() { nullMask.setAllNull(); }
    void setAllNonNull() { nullMask.setAllNonNull(); }
    // On return true, there are no null. On return false, there may or may not be nulls.
    bool hasNoNullsGuarantee() const { return nullMask.hasNoNullsGuarantee(); }
    void setNullRange(uint32_t startPos, uint32_t len, bool value) {
        nullMask.setNullFromRange(startPos, len, value);
    }
    const NullMask& getNullMask() const { return nullMask; }
    void setNull(uint32_t pos, bool isNull);
    uint8_t isNull(uint32_t pos) const { return nullMask.isNull(pos); }
    void setAsSingleNullEntry() {
        state->getSelVectorUnsafe().setSelSize(1);
        setNull(state->getSelVector()[0], true);
    }

    bool setNullFromBits(const uint64_t* srcNullEntries, uint64_t srcOffset, uint64_t dstOffset,
        uint64_t numBitsToCopy, bool invert = false);

    uint32_t getNumBytesPerValue() const { return numBytesPerValue; }

    // TODO(Guodong): Rename this to getValueRef
    template<typename T>
    const T& getValue(uint32_t pos) const {
        return ((T*)valueBuffer.get())[pos];
    }
    template<typename T>
    T& getValue(uint32_t pos) {
        return ((T*)valueBuffer.get())[pos];
    }
    template<typename T>
    void setValue(uint32_t pos, T val);
    // copyFromRowData assumes rowData is non-NULL.
    void copyFromRowData(uint32_t pos, const uint8_t* rowData);
    // copyToRowData assumes srcVectorData is non-NULL.
    void copyToRowData(uint32_t pos, uint8_t* rowData,
        InMemOverflowBuffer* rowOverflowBuffer) const;
    // copyFromVectorData assumes srcVectorData is non-NULL.
    void copyFromVectorData(uint8_t* dstData, const ValueVector* srcVector,
        const uint8_t* srcVectorData);
    void copyFromVectorData(uint64_t dstPos, const ValueVector* srcVector, uint64_t srcPos);
    void copyFromValue(uint64_t pos, const Value& value);

    std::unique_ptr<Value> getAsValue(uint64_t pos) const;

    uint8_t* getData() const { return valueBuffer.get(); }

    offset_t readNodeOffset(uint32_t pos) const {
        KU_ASSERT(dataType.getLogicalTypeID() == LogicalTypeID::INTERNAL_ID);
        return getValue<nodeID_t>(pos).offset;
    }

    void resetAuxiliaryBuffer();

    // If there is still non-null values after discarding, return true. Otherwise, return false.
    // For an unflat vector, its selection vector is also updated to the resultSelVector.
    static bool discardNull(ValueVector& vector);

    void serialize(Serializer& ser) const;
    static std::unique_ptr<ValueVector> deSerialize(Deserializer& deSer, storage::MemoryManager* mm,
        std::shared_ptr<DataChunkState> dataChunkState);

    SelectionVector* getSelVectorPtr() const {
        return state ? &state->getSelVectorUnsafe() : nullptr;
    }

private:
    uint32_t getDataTypeSize(const LogicalType& type);
    void initializeValueBuffer();

public:
    LogicalType dataType;
    std::shared_ptr<DataChunkState> state;

private:
    std::unique_ptr<uint8_t[]> valueBuffer;
    NullMask nullMask;
    uint32_t numBytesPerValue;
    std::unique_ptr<AuxiliaryBuffer> auxiliaryBuffer;
};

class LBUG_API StringVector {
public:
    static inline InMemOverflowBuffer* getInMemOverflowBuffer(ValueVector* vector) {
        KU_ASSERT(vector->dataType.getPhysicalType() == PhysicalTypeID::STRING);
        return ku_dynamic_cast<StringAuxiliaryBuffer*>(vector->auxiliaryBuffer.get())
            ->getOverflowBuffer();
    }

    static void addString(ValueVector* vector, uint32_t vectorPos, ku_string_t& srcStr);
    static void addString(ValueVector* vector, uint32_t vectorPos, const char* srcStr,
        uint64_t length);
    static void addString(ValueVector* vector, uint32_t vectorPos, std::string_view srcStr);
    // Add empty string with space reserved for the provided size
    // Returned value can be modified to set the string contents
    static ku_string_t& reserveString(ValueVector* vector, uint32_t vectorPos, uint64_t length);
    static void reserveString(ValueVector* vector, ku_string_t& dstStr, uint64_t length);
    static void addString(ValueVector* vector, ku_string_t& dstStr, ku_string_t& srcStr);
    static void addString(ValueVector* vector, ku_string_t& dstStr, const char* srcStr,
        uint64_t length);
    static void addString(lbug::common::ValueVector* vector, ku_string_t& dstStr,
        const std::string& srcStr);
    static void copyToRowData(const ValueVector* vector, uint32_t pos, uint8_t* rowData,
        InMemOverflowBuffer* rowOverflowBuffer);
};

struct LBUG_API BlobVector {
    static void addBlob(ValueVector* vector, uint32_t pos, const char* data, uint32_t length) {
        StringVector::addString(vector, pos, data, length);
    } // namespace common
    static void addBlob(ValueVector* vector, uint32_t pos, const uint8_t* data, uint64_t length) {
        StringVector::addString(vector, pos, reinterpret_cast<const char*>(data), length);
    }
}; // namespace lbug

// ListVector is used for both LIST and ARRAY physical type
class LBUG_API ListVector {
public:
    static const ListAuxiliaryBuffer& getAuxBuffer(const ValueVector& vector) {
        return vector.auxiliaryBuffer->constCast<ListAuxiliaryBuffer>();
    }
    static ListAuxiliaryBuffer& getAuxBufferUnsafe(const ValueVector& vector) {
        return vector.auxiliaryBuffer->cast<ListAuxiliaryBuffer>();
    }
    // If you call setDataVector during initialize, there must be a followed up
    // copyListEntryAndBufferMetaData at runtime.
    // TODO(Xiyang): try to merge setDataVector & copyListEntryAndBufferMetaData
    static void setDataVector(const ValueVector* vector, std::shared_ptr<ValueVector> dataVector) {
        KU_ASSERT(validateType(*vector));
        auto& listBuffer = getAuxBufferUnsafe(*vector);
        listBuffer.setDataVector(std::move(dataVector));
    }
    static void copyListEntryAndBufferMetaData(ValueVector& vector,
        const SelectionVector& selVector, const ValueVector& other,
        const SelectionVector& otherSelVector);
    static ValueVector* getDataVector(const ValueVector* vector) {
        KU_ASSERT(validateType(*vector));
        return getAuxBuffer(*vector).getDataVector();
    }
    static std::shared_ptr<ValueVector> getSharedDataVector(const ValueVector* vector) {
        KU_ASSERT(validateType(*vector));
        return getAuxBuffer(*vector).getSharedDataVector();
    }
    static uint64_t getDataVectorSize(const ValueVector* vector) {
        KU_ASSERT(validateType(*vector));
        return getAuxBuffer(*vector).getSize();
    }
    static uint8_t* getListValues(const ValueVector* vector, const list_entry_t& listEntry) {
        KU_ASSERT(validateType(*vector));
        auto dataVector = getDataVector(vector);
        return dataVector->getData() + dataVector->getNumBytesPerValue() * listEntry.offset;
    }
    static uint8_t* getListValuesWithOffset(const ValueVector* vector,
        const list_entry_t& listEntry, offset_t elementOffsetInList) {
        KU_ASSERT(validateType(*vector));
        return getListValues(vector, listEntry) +
               elementOffsetInList * getDataVector(vector)->getNumBytesPerValue();
    }
    static list_entry_t addList(ValueVector* vector, uint64_t listSize) {
        KU_ASSERT(validateType(*vector));
        return getAuxBufferUnsafe(*vector).addList(listSize);
    }
    static void resizeDataVector(ValueVector* vector, uint64_t numValues) {
        KU_ASSERT(validateType(*vector));
        getAuxBufferUnsafe(*vector).resize(numValues);
    }

    static void copyFromRowData(ValueVector* vector, uint32_t pos, const uint8_t* rowData);
    static void copyToRowData(const ValueVector* vector, uint32_t pos, uint8_t* rowData,
        InMemOverflowBuffer* rowOverflowBuffer);
    static void copyFromVectorData(ValueVector* dstVector, uint8_t* dstData,
        const ValueVector* srcVector, const uint8_t* srcData);
    static void appendDataVector(ValueVector* dstVector, ValueVector* srcDataVector,
        uint64_t numValuesToAppend);
    static void sliceDataVector(ValueVector* vectorToSlice, uint64_t offset, uint64_t numValues);

private:
    static bool validateType(const ValueVector& vector) {
        switch (vector.dataType.getPhysicalType()) {
        case PhysicalTypeID::LIST:
        case PhysicalTypeID::ARRAY:
            return true;
        default:
            return false;
        }
    }
};

class StructVector {
public:
    static const std::vector<std::shared_ptr<ValueVector>>& getFieldVectors(
        const ValueVector* vector) {
        return ku_dynamic_cast<StructAuxiliaryBuffer*>(vector->auxiliaryBuffer.get())
            ->getFieldVectors();
    }

    static std::shared_ptr<ValueVector> getFieldVector(const ValueVector* vector,
        struct_field_idx_t idx) {
        return ku_dynamic_cast<StructAuxiliaryBuffer*>(vector->auxiliaryBuffer.get())
            ->getFieldVectorShared(idx);
    }

    static ValueVector* getFieldVectorRaw(const ValueVector& vector, const std::string& fieldName) {
        auto idx = StructType::getFieldIdx(vector.dataType, fieldName);
        return ku_dynamic_cast<StructAuxiliaryBuffer*>(vector.auxiliaryBuffer.get())
            ->getFieldVectorPtr(idx);
    }

    static void referenceVector(ValueVector* vector, struct_field_idx_t idx,
        std::shared_ptr<ValueVector> vectorToReference) {
        ku_dynamic_cast<StructAuxiliaryBuffer*>(vector->auxiliaryBuffer.get())
            ->referenceChildVector(idx, std::move(vectorToReference));
    }

    static void copyFromRowData(ValueVector* vector, uint32_t pos, const uint8_t* rowData);
    static void copyToRowData(const ValueVector* vector, uint32_t pos, uint8_t* rowData,
        InMemOverflowBuffer* rowOverflowBuffer);
    static void copyFromVectorData(ValueVector* dstVector, const uint8_t* dstData,
        const ValueVector* srcVector, const uint8_t* srcData);
};

class UnionVector {
public:
    static inline ValueVector* getTagVector(const ValueVector* vector) {
        KU_ASSERT(vector->dataType.getLogicalTypeID() == LogicalTypeID::UNION);
        return StructVector::getFieldVector(vector, UnionType::TAG_FIELD_IDX).get();
    }

    static inline ValueVector* getValVector(const ValueVector* vector, union_field_idx_t fieldIdx) {
        KU_ASSERT(vector->dataType.getLogicalTypeID() == LogicalTypeID::UNION);
        return StructVector::getFieldVector(vector, UnionType::getInternalFieldIdx(fieldIdx)).get();
    }

    static inline std::shared_ptr<ValueVector> getSharedValVector(const ValueVector* vector,
        union_field_idx_t fieldIdx) {
        KU_ASSERT(vector->dataType.getLogicalTypeID() == LogicalTypeID::UNION);
        return StructVector::getFieldVector(vector, UnionType::getInternalFieldIdx(fieldIdx));
    }

    static inline void referenceVector(ValueVector* vector, union_field_idx_t fieldIdx,
        std::shared_ptr<ValueVector> vectorToReference) {
        StructVector::referenceVector(vector, UnionType::getInternalFieldIdx(fieldIdx),
            std::move(vectorToReference));
    }

    static inline void setTagField(ValueVector& vector, SelectionVector& sel,
        union_field_idx_t tag) {
        KU_ASSERT(vector.dataType.getLogicalTypeID() == LogicalTypeID::UNION);
        for (auto i = 0u; i < sel.getSelSize(); i++) {
            vector.setValue<struct_field_idx_t>(sel[i], tag);
        }
    }
};

class MapVector {
public:
    static inline ValueVector* getKeyVector(const ValueVector* vector) {
        return StructVector::getFieldVector(ListVector::getDataVector(vector), 0 /* keyVectorPos */)
            .get();
    }

    static inline ValueVector* getValueVector(const ValueVector* vector) {
        return StructVector::getFieldVector(ListVector::getDataVector(vector), 1 /* valVectorPos */)
            .get();
    }

    static inline uint8_t* getMapKeys(const ValueVector* vector, const list_entry_t& listEntry) {
        auto keyVector = getKeyVector(vector);
        return keyVector->getData() + keyVector->getNumBytesPerValue() * listEntry.offset;
    }

    static inline uint8_t* getMapValues(const ValueVector* vector, const list_entry_t& listEntry) {
        auto valueVector = getValueVector(vector);
        return valueVector->getData() + valueVector->getNumBytesPerValue() * listEntry.offset;
    }
};

} // namespace common
} // namespace lbug
