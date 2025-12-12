#pragma once

#include "common/data_chunk/data_chunk.h"
#include "processor/data_pos.h"

namespace lbug {
namespace processor {

struct AggregateInfo {
    DataPos aggVectorPos;
    std::vector<data_chunk_pos_t> multiplicityChunksPos;
    common::LogicalType distinctAggKeyType;

    AggregateInfo(const DataPos& aggVectorPos, std::vector<data_chunk_pos_t> multiplicityChunksPos,
        common::LogicalType distinctAggKeyType)
        : aggVectorPos{aggVectorPos}, multiplicityChunksPos{std::move(multiplicityChunksPos)},
          distinctAggKeyType{std::move(distinctAggKeyType)} {}
    EXPLICIT_COPY_DEFAULT_MOVE(AggregateInfo);

private:
    AggregateInfo(const AggregateInfo& other)
        : aggVectorPos{other.aggVectorPos}, multiplicityChunksPos{other.multiplicityChunksPos},
          distinctAggKeyType{other.distinctAggKeyType.copy()} {}
};

struct AggregateInput {
    common::ValueVector* aggregateVector;
    std::vector<common::DataChunk*> multiplicityChunks;

    AggregateInput() : aggregateVector{nullptr} {}
    EXPLICIT_COPY_DEFAULT_MOVE(AggregateInput);

private:
    AggregateInput(const AggregateInput& other)
        : aggregateVector{other.aggregateVector}, multiplicityChunks{other.multiplicityChunks} {}
};

} // namespace processor
} // namespace lbug
