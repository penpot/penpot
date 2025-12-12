#pragma once

#include <utility>

#include "common/types/types.h"

namespace lbug {
namespace processor {

using data_chunk_pos_t = common::idx_t;
constexpr data_chunk_pos_t INVALID_DATA_CHUNK_POS = common::INVALID_IDX;
using value_vector_pos_t = common::idx_t;
constexpr value_vector_pos_t INVALID_VALUE_VECTOR_POS = common::INVALID_IDX;

struct DataPos {
    data_chunk_pos_t dataChunkPos;
    value_vector_pos_t valueVectorPos;

    DataPos() : dataChunkPos{INVALID_DATA_CHUNK_POS}, valueVectorPos{INVALID_VALUE_VECTOR_POS} {}
    explicit DataPos(data_chunk_pos_t dataChunkPos, value_vector_pos_t valueVectorPos)
        : dataChunkPos{dataChunkPos}, valueVectorPos{valueVectorPos} {}
    explicit DataPos(std::pair<data_chunk_pos_t, value_vector_pos_t> pos)
        : dataChunkPos{pos.first}, valueVectorPos{pos.second} {}

    static DataPos getInvalidPos() { return DataPos(); }
    bool isValid() const {
        return dataChunkPos != INVALID_DATA_CHUNK_POS && valueVectorPos != INVALID_VALUE_VECTOR_POS;
    }

    inline bool operator==(const DataPos& rhs) const {
        return (dataChunkPos == rhs.dataChunkPos) && (valueVectorPos == rhs.valueVectorPos);
    }
};

} // namespace processor
} // namespace lbug
