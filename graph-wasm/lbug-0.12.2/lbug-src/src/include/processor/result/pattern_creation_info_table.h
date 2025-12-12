#pragma once

#include "processor/operator/aggregate/aggregate_hash_table.h"

namespace lbug {
namespace processor {

struct PatternCreationInfo {
    uint8_t* tuple;
    bool hasCreated;

    common::nodeID_t getPatternID(common::executor_id_t matchExecutorID) const {
        auto ftColIndex = matchExecutorID;
        return *(common::nodeID_t*)(tuple + ftColIndex * sizeof(common::nodeID_t));
    }

    void updateID(common::executor_id_t executorID, common::executor_info executorInfo,
        common::nodeID_t nodeID) const;
};

class PatternCreationInfoTable : public AggregateHashTable {
public:
    PatternCreationInfoTable(storage::MemoryManager& memoryManager,
        std::vector<common::LogicalType> keyTypes, FactorizedTableSchema tableSchema);

    PatternCreationInfo getPatternCreationInfo(const std::vector<common::ValueVector*>& keyVectors);

    uint64_t matchFTEntries(std::span<const common::ValueVector*> keyVectors,
        uint64_t numMayMatches, uint64_t numNoMatches) override;

private:
    uint8_t* tuple;
    ft_col_offset_t idColOffset;
};

} // namespace processor
} // namespace lbug
