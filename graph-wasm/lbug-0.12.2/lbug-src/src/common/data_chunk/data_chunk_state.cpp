#include "common/data_chunk/data_chunk_state.h"

#include "common/system_config.h"

namespace lbug {
namespace common {

DataChunkState::DataChunkState() : DataChunkState(DEFAULT_VECTOR_CAPACITY) {}

std::shared_ptr<DataChunkState> DataChunkState::getSingleValueDataChunkState() {
    auto state = std::make_shared<DataChunkState>(1);
    state->initOriginalAndSelectedSize(1);
    state->setToFlat();
    return state;
}

} // namespace common
} // namespace lbug
