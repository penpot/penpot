#include "processor/result/result_set.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

ResultSet::ResultSet(ResultSetDescriptor* resultSetDescriptor,
    storage::MemoryManager* memoryManager)
    : multiplicity{1} {
    auto numDataChunks = resultSetDescriptor->dataChunkDescriptors.size();
    dataChunks.resize(numDataChunks);
    for (auto i = 0u; i < numDataChunks; ++i) {
        auto dataChunkDescriptor = resultSetDescriptor->dataChunkDescriptors[i].get();
        auto numValueVectors = dataChunkDescriptor->logicalTypes.size();
        auto dataChunk = std::make_unique<DataChunk>(numValueVectors);
        if (dataChunkDescriptor->isSingleState) {
            dataChunk->state = DataChunkState::getSingleValueDataChunkState();
        }
        for (auto j = 0u; j < numValueVectors; ++j) {
            auto vector = std::make_shared<ValueVector>(dataChunkDescriptor->logicalTypes[j].copy(),
                memoryManager);
            dataChunk->insert(j, std::move(vector));
        }
        insert(i, std::move(dataChunk));
    }
}

uint64_t ResultSet::getNumTuplesWithoutMultiplicity(
    const std::unordered_set<uint32_t>& dataChunksPosInScope) {
    KU_ASSERT(!dataChunksPosInScope.empty());
    uint64_t numTuples = 1;
    for (auto& dataChunkPos : dataChunksPosInScope) {
        numTuples *= dataChunks[dataChunkPos]->state->getSelVector().getSelSize();
    }
    return numTuples;
}

} // namespace processor
} // namespace lbug
