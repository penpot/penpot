#include "common/data_chunk/data_chunk.h"

namespace lbug {
namespace common {

void DataChunk::insert(uint32_t pos, std::shared_ptr<ValueVector> valueVector) {
    valueVector->setState(state);
    KU_ASSERT(valueVectors.size() > pos);
    valueVectors[pos] = std::move(valueVector);
}

void DataChunk::resetAuxiliaryBuffer() {
    for (auto& valueVector : valueVectors) {
        valueVector->resetAuxiliaryBuffer();
    }
}

} // namespace common
} // namespace lbug
