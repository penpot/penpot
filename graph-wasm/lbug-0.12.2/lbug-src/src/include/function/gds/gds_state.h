#pragma once

#include "auxiliary_state/gds_auxilary_state.h"
#include "gds_frontier.h"

namespace lbug {
namespace function {

struct GDSComputeState {
    std::shared_ptr<FrontierPair> frontierPair = nullptr;
    std::unique_ptr<EdgeCompute> edgeCompute = nullptr;
    std::unique_ptr<GDSAuxiliaryState> auxiliaryState = nullptr;

    GDSComputeState(std::shared_ptr<FrontierPair> frontierPair,
        std::unique_ptr<EdgeCompute> edgeCompute, std::unique_ptr<GDSAuxiliaryState> auxiliaryState)
        : frontierPair{std::move(frontierPair)}, edgeCompute{std::move(edgeCompute)},
          auxiliaryState{std::move(auxiliaryState)} {}

    void initSource(common::nodeID_t sourceNodeID) const;
    // When performing computations on multi-label graphs, it is beneficial to fix a single
    // node table of nodes in the current frontier and a single node table of nodes for the next
    // frontier. That is because algorithms will perform extensions using a single relationship
    // table at a time, and each relationship table R is between a single source node table S and
    // a single destination node table T. Therefore, during execution the algorithm will need to
    // check only the active S nodes in current frontier and update the active statuses of only the
    // T nodes in the next frontier. The information that the algorithm is beginning and S-to-T
    // extensions are be given to the data structures of the computation, e.g., FrontierPairs and
    // RJOutputs, to possibly avoid them doing lookups of S and T-related data structures,
    // e.g., maps, internally.
    void beginFrontierCompute(common::table_id_t currTableID, common::table_id_t nextTableID) const;

    // Switch all data structures (frontierPair & auxiliaryState) to dense version.
    void switchToDense(processor::ExecutionContext* context, graph::Graph* graph) const;
};

} // namespace function
} // namespace lbug
