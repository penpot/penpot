#include "processor/operator/filtering_operator.h"

#include <cstring>

#include "common/data_chunk/data_chunk_state.h"
#include "common/system_config.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

SelVectorOverWriter::SelVectorOverWriter() {
    currentSelVector = std::make_shared<common::SelectionVector>(common::DEFAULT_VECTOR_CAPACITY);
}

void SelVectorOverWriter::restoreSelVector(DataChunkState& dataChunkState) const {
    if (prevSelVector != nullptr) {
        dataChunkState.setSelVector(prevSelVector);
    }
}

void SelVectorOverWriter::saveSelVector(DataChunkState& dataChunkState) {
    if (prevSelVector == nullptr) {
        prevSelVector = dataChunkState.getSelVectorShared();
    }
    resetCurrentSelVector(dataChunkState.getSelVector());
    dataChunkState.setSelVector(currentSelVector);
}

void SelVectorOverWriter::resetCurrentSelVector(const SelectionVector& selVector) {
    currentSelVector->setSelSize(selVector.getSelSize());
    if (selVector.isUnfiltered()) {
        currentSelVector->setToUnfiltered();
    } else {
        std::memcpy(currentSelVector->getMutableBuffer().data(),
            selVector.getSelectedPositions().data(), selVector.getSelSize() * sizeof(sel_t));
        currentSelVector->setToFiltered();
    }
}

} // namespace processor
} // namespace lbug
