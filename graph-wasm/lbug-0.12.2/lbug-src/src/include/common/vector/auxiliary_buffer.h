#pragma once

#include "common/api.h"
#include "common/in_mem_overflow_buffer.h"
#include "common/types/types.h"

namespace lbug {
namespace common {

class ValueVector;

// AuxiliaryBuffer holds data which is only used by the targeting dataType.
class LBUG_API AuxiliaryBuffer {
public:
    virtual ~AuxiliaryBuffer() = default;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }

    template<class TARGET>
    const TARGET& constCast() const {
        return common::ku_dynamic_cast<const TARGET&>(*this);
    }
};

class StringAuxiliaryBuffer : public AuxiliaryBuffer {
public:
    explicit StringAuxiliaryBuffer(storage::MemoryManager* memoryManager) {
        inMemOverflowBuffer = std::make_unique<InMemOverflowBuffer>(memoryManager);
    }

    InMemOverflowBuffer* getOverflowBuffer() const { return inMemOverflowBuffer.get(); }
    uint8_t* allocateOverflow(uint64_t size) { return inMemOverflowBuffer->allocateSpace(size); }
    void resetOverflowBuffer() const { inMemOverflowBuffer->resetBuffer(); }

private:
    std::unique_ptr<InMemOverflowBuffer> inMemOverflowBuffer;
};

class LBUG_API StructAuxiliaryBuffer : public AuxiliaryBuffer {
public:
    StructAuxiliaryBuffer(const LogicalType& type, storage::MemoryManager* memoryManager);

    void referenceChildVector(idx_t idx, std::shared_ptr<ValueVector> vectorToReference) {
        childrenVectors[idx] = std::move(vectorToReference);
    }
    const std::vector<std::shared_ptr<ValueVector>>& getFieldVectors() const {
        return childrenVectors;
    }
    std::shared_ptr<ValueVector> getFieldVectorShared(idx_t idx) const {
        return childrenVectors[idx];
    }
    ValueVector* getFieldVectorPtr(idx_t idx) const { return childrenVectors[idx].get(); }

private:
    std::vector<std::shared_ptr<ValueVector>> childrenVectors;
};

// ListVector layout:
// To store a list value in the valueVector, we could use two separate vectors.
// 1. A vector(called offset vector) for the list offsets and length(called list_entry_t): This
// vector contains the starting indices and length for each list within the data vector.
// 2. A data vector(called dataVector) to store the actual list elements: This vector holds the
// actual elements of the lists in a flat, continuous storage. Each list would be represented as a
// contiguous subsequence of elements in this vector.
class LBUG_API ListAuxiliaryBuffer : public AuxiliaryBuffer {
    friend class ListVector;

public:
    ListAuxiliaryBuffer(const LogicalType& dataVectorType, storage::MemoryManager* memoryManager);

    void setDataVector(std::shared_ptr<ValueVector> vector) { dataVector = std::move(vector); }
    ValueVector* getDataVector() const { return dataVector.get(); }
    std::shared_ptr<ValueVector> getSharedDataVector() const { return dataVector; }

    list_entry_t addList(list_size_t listSize);

    uint64_t getSize() const { return size; }

    void resetSize() { size = 0; }

    void resize(uint64_t numValues);

private:
    void resizeDataVector(ValueVector* dataVector);

    void resizeStructDataVector(ValueVector* dataVector);

private:
    uint64_t capacity;
    uint64_t size;

    std::shared_ptr<ValueVector> dataVector;
};

class AuxiliaryBufferFactory {
public:
    static std::unique_ptr<AuxiliaryBuffer> getAuxiliaryBuffer(LogicalType& type,
        storage::MemoryManager* memoryManager);
};

} // namespace common
} // namespace lbug
