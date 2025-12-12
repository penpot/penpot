#pragma once

#include "function/gds/bfs_graph.h"
#include "gds_auxilary_state.h"

namespace lbug {
namespace function {

class PathAuxiliaryState : public GDSAuxiliaryState {
public:
    explicit PathAuxiliaryState(std::unique_ptr<BFSGraphManager> bfsGraphManager)
        : bfsGraphManager{std::move(bfsGraphManager)} {}

    BFSGraphManager* getBFSGraphManager() { return bfsGraphManager.get(); }

    void beginFrontierCompute(common::table_id_t, common::table_id_t toTableID) override {
        bfsGraphManager->getCurrentGraph()->pinTableID(toTableID);
    }

    void switchToDense(processor::ExecutionContext* context, graph::Graph* graph) override {
        bfsGraphManager->switchToDense(context, graph);
    }

private:
    std::unique_ptr<BFSGraphManager> bfsGraphManager;
};

class WSPPathsAuxiliaryState : public GDSAuxiliaryState {
public:
    explicit WSPPathsAuxiliaryState(std::unique_ptr<BFSGraphManager> bfsGraphManager)
        : bfsGraphManager{std::move(bfsGraphManager)} {}

    BFSGraphManager* getBFSGraphManager() { return bfsGraphManager.get(); }

    void initSource(common::nodeID_t sourceNodeID) override {
        sourceParent.setCost(0);
        bfsGraphManager->getCurrentGraph()->pinTableID(sourceNodeID.tableID);
        bfsGraphManager->getCurrentGraph()->setParentList(sourceNodeID.offset, &sourceParent);
    }

    void beginFrontierCompute(common::table_id_t, common::table_id_t toTableID) override {
        bfsGraphManager->getCurrentGraph()->pinTableID(toTableID);
    }

    void switchToDense(processor::ExecutionContext* context, graph::Graph* graph) override {
        bfsGraphManager->switchToDense(context, graph);
    }

private:
    std::unique_ptr<BFSGraphManager> bfsGraphManager;
    ParentList sourceParent;
};

} // namespace function
} // namespace lbug
