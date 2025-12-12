#pragma once

#include <memory>
#include <vector>

#include "common/copy_constructors.h"
#include "common/data_chunk/data_chunk_state.h"
#include "common/vector/value_vector.h"

namespace lbug {
namespace common {

// A DataChunk represents tuples as a set of value vectors and a selector array.
// The data chunk represents a subset of a relation i.e., a set of tuples as
// lists of the same length. It is appended into DataChunks and passed as intermediate
// representations between operators.
// A data chunk further contains a DataChunkState, which keeps the data chunk's size, selector, and
// currIdx (used when flattening and implies the value vector only contains the elements at currIdx
// of each value vector).
class LBUG_API DataChunk {
public:
    DataChunk() : DataChunk{0} {}
    explicit DataChunk(uint32_t numValueVectors)
        : DataChunk(numValueVectors, std::make_shared<DataChunkState>()){};

    DataChunk(uint32_t numValueVectors, const std::shared_ptr<DataChunkState>& state)
        : valueVectors(numValueVectors), state{state} {};
    DELETE_COPY_DEFAULT_MOVE(DataChunk);

    void insert(uint32_t pos, std::shared_ptr<ValueVector> valueVector);

    void resetAuxiliaryBuffer();

    uint32_t getNumValueVectors() const { return valueVectors.size(); }

    const ValueVector& getValueVector(uint64_t valueVectorPos) const {
        return *valueVectors[valueVectorPos];
    }
    ValueVector& getValueVectorMutable(uint64_t valueVectorPos) const {
        return *valueVectors[valueVectorPos];
    }

public:
    std::vector<std::shared_ptr<ValueVector>> valueVectors;
    std::shared_ptr<DataChunkState> state;
};

} // namespace common
} // namespace lbug
