#include "function/gds/gds_state.h"

namespace lbug {
namespace function {

void GDSComputeState::initSource(common::nodeID_t sourceNodeID) const {
    frontierPair->pinNextFrontier(sourceNodeID.tableID);
    frontierPair->addNodeToNextFrontier(sourceNodeID);
    frontierPair->setActiveNodesForNextIter();
    auxiliaryState->initSource(sourceNodeID);
}

void GDSComputeState::beginFrontierCompute(common::table_id_t currTableID,
    common::table_id_t nextTableID) const {
    frontierPair->beginFrontierComputeBetweenTables(currTableID, nextTableID);
    auxiliaryState->beginFrontierCompute(currTableID, nextTableID);
}

void GDSComputeState::switchToDense(processor::ExecutionContext* context,
    graph::Graph* graph) const {
    frontierPair->switchToDense(context, graph);
    auxiliaryState->switchToDense(context, graph);
}

} // namespace function
} // namespace lbug
