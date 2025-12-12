#pragma once

#include "common/enums/extend_direction.h"
#include "processor/operator/hash_join/hash_join_build.h"
#include "processor/operator/physical_operator.h"

namespace lbug {
namespace processor {

struct PathPropertyProbeSharedState {
    std::shared_ptr<HashJoinSharedState> nodeHashTableState;
    std::shared_ptr<HashJoinSharedState> relHashTableState;

    PathPropertyProbeSharedState(std::shared_ptr<HashJoinSharedState> nodeHashTableState,
        std::shared_ptr<HashJoinSharedState> relHashTableState)
        : nodeHashTableState{std::move(nodeHashTableState)},
          relHashTableState{std::move(relHashTableState)} {}
};

struct PathPropertyProbeLocalState {
    std::unique_ptr<common::hash_t[]> hashes;
    std::unique_ptr<uint8_t*[]> probedTuples;
    std::unique_ptr<uint8_t*[]> matchedTuples;

    PathPropertyProbeLocalState() {
        hashes = std::make_unique<common::hash_t[]>(common::DEFAULT_VECTOR_CAPACITY);
        probedTuples = std::make_unique<uint8_t*[]>(common::DEFAULT_VECTOR_CAPACITY);
        matchedTuples = std::make_unique<uint8_t*[]>(common::DEFAULT_VECTOR_CAPACITY);
    }
};

struct PathPropertyProbeInfo {
    DataPos pathPos = DataPos();

    DataPos leftNodeIDPos = DataPos();
    DataPos rightNodeIDPos = DataPos();
    DataPos inputNodeIDsPos = DataPos();
    DataPos inputEdgeIDsPos = DataPos();
    DataPos directionPos = DataPos();

    std::unordered_map<common::table_id_t, std::string> tableIDToName;

    std::vector<common::struct_field_idx_t> nodeFieldIndices;
    std::vector<common::struct_field_idx_t> relFieldIndices;
    std::vector<ft_col_idx_t> nodeTableColumnIndices;
    std::vector<ft_col_idx_t> relTableColumnIndices;

    common::ExtendDirection extendDirection = common::ExtendDirection::FWD;
    bool extendFromLeft = false;

    PathPropertyProbeInfo() = default;
    EXPLICIT_COPY_DEFAULT_MOVE(PathPropertyProbeInfo);

private:
    PathPropertyProbeInfo(const PathPropertyProbeInfo& other) {
        pathPos = other.pathPos;
        leftNodeIDPos = other.leftNodeIDPos;
        rightNodeIDPos = other.rightNodeIDPos;
        inputNodeIDsPos = other.inputNodeIDsPos;
        inputEdgeIDsPos = other.inputEdgeIDsPos;
        directionPos = other.directionPos;
        tableIDToName = other.tableIDToName;
        nodeFieldIndices = other.nodeFieldIndices;
        relFieldIndices = other.relFieldIndices;
        nodeTableColumnIndices = other.nodeTableColumnIndices;
        relTableColumnIndices = other.relTableColumnIndices;
        extendDirection = other.extendDirection;
        extendFromLeft = other.extendFromLeft;
    }
};

class PathPropertyProbe : public PhysicalOperator {
    static constexpr PhysicalOperatorType type_ = PhysicalOperatorType::PATH_PROPERTY_PROBE;

public:
    PathPropertyProbe(PathPropertyProbeInfo info,
        std::shared_ptr<PathPropertyProbeSharedState> sharedState,
        std::unique_ptr<PhysicalOperator> probeChild, uint32_t id,
        std::unique_ptr<OPPrintInfo> printInfo)
        : PhysicalOperator{type_, std::move(probeChild), id, std::move(printInfo)},
          info{std::move(info)}, sharedState{std::move(sharedState)} {}

    void initLocalStateInternal(ResultSet* resultSet_, ExecutionContext* context) final;

    bool getNextTuplesInternal(ExecutionContext* context) final;

    std::unique_ptr<PhysicalOperator> copy() final {
        return std::make_unique<PathPropertyProbe>(info.copy(), sharedState, children[0]->copy(),
            id, printInfo->copy());
    }

private:
    void probe(JoinHashTable* hashTable, uint64_t sizeProbed, uint64_t sizeToProbe,
        common::ValueVector* idVector, const std::vector<common::ValueVector*>& propertyVectors,
        const std::vector<ft_col_idx_t>& colIndicesToScan) const;

private:
    PathPropertyProbeInfo info;
    std::shared_ptr<PathPropertyProbeSharedState> sharedState;
    PathPropertyProbeLocalState localState;

    common::ValueVector* pathNodesVector = nullptr;
    common::ValueVector* pathRelsVector = nullptr;
    common::ValueVector* pathNodeIDsDataVector = nullptr;
    common::ValueVector* pathNodeLabelsDataVector = nullptr;
    common::ValueVector* pathRelIDsDataVector = nullptr;
    common::ValueVector* pathRelLabelsDataVector = nullptr;
    common::ValueVector* pathSrcNodeIDsDataVector = nullptr;
    common::ValueVector* pathDstNodeIDsDataVector = nullptr;

    std::vector<common::ValueVector*> pathNodesPropertyDataVectors;
    std::vector<common::ValueVector*> pathRelsPropertyDataVectors;

    common::ValueVector* inputLeftNodeIDVector = nullptr;
    common::ValueVector* inputRightNodeIDVector = nullptr;
    common::ValueVector* inputNodeIDsVector = nullptr;
    common::ValueVector* inputRelIDsVector = nullptr;
    common::ValueVector* inputDirectionVector = nullptr;
};

} // namespace processor
} // namespace lbug
