#pragma once

#include <utility>

#include "common/enums/extend_direction.h"
#include "common/task_system/task.h"
#include "frontier_morsel.h"
#include "function/gds/gds_frontier.h"
#include "graph/graph.h"

namespace lbug {
namespace function {

struct FrontierTaskInfo {
    common::table_id_t srcTableID;
    common::table_id_t dstTableID;
    catalog::TableCatalogEntry* relGroupEntry = nullptr;
    graph::Graph* graph;
    common::ExtendDirection direction;
    EdgeCompute& edgeCompute;
    std::vector<std::string> propertiesToScan;

    FrontierTaskInfo(common::table_id_t srcTableID, common::table_id_t dstTableID,
        catalog::TableCatalogEntry* relGroupEntry, graph::Graph* graph,
        common::ExtendDirection direction, EdgeCompute& edgeCompute,
        std::vector<std::string> propertiesToScan)
        : srcTableID{srcTableID}, dstTableID{dstTableID}, relGroupEntry{relGroupEntry},
          graph{graph}, direction{direction}, edgeCompute{edgeCompute},
          propertiesToScan{std::move(propertiesToScan)} {}
    FrontierTaskInfo(const FrontierTaskInfo& other)
        : srcTableID{other.srcTableID}, dstTableID{other.dstTableID},
          relGroupEntry{other.relGroupEntry}, graph{other.graph}, direction{other.direction},
          edgeCompute{other.edgeCompute}, propertiesToScan{other.propertiesToScan} {}

    common::table_id_t getBoundTableID() const;
    common::table_id_t getNbrTableID() const;
    common::oid_t getRelTableID() const;
};

struct FrontierTaskSharedState {
    FrontierMorselDispatcher morselDispatcher;
    FrontierPair& frontierPair;

    FrontierTaskSharedState(uint64_t maxNumThreads, FrontierPair& frontierPair)
        : morselDispatcher{maxNumThreads}, frontierPair{frontierPair} {}
    DELETE_COPY_AND_MOVE(FrontierTaskSharedState);
};

class FrontierTask : public common::Task {
public:
    FrontierTask(uint64_t maxNumThreads, const FrontierTaskInfo& info,
        std::shared_ptr<FrontierTaskSharedState> sharedState)
        : Task{maxNumThreads}, info{info}, sharedState{std::move(sharedState)} {}

    void run() override;

    void runSparse();

private:
    FrontierTaskInfo info;
    std::shared_ptr<FrontierTaskSharedState> sharedState;
};

struct VertexComputeTaskSharedState {
    FrontierMorselDispatcher morselDispatcher;

    explicit VertexComputeTaskSharedState(uint64_t maxNumThreads)
        : morselDispatcher{maxNumThreads} {}
};

struct VertexComputeTaskInfo {
    VertexCompute& vc;
    graph::Graph* graph;
    catalog::TableCatalogEntry* tableEntry;
    std::vector<std::string> propertiesToScan;

    VertexComputeTaskInfo(VertexCompute& vc, graph::Graph* graph,
        catalog::TableCatalogEntry* tableEntry, std::vector<std::string> propertiesToScan)
        : vc{vc}, graph{graph}, tableEntry{tableEntry},
          propertiesToScan{std::move(propertiesToScan)} {}
    VertexComputeTaskInfo(const VertexComputeTaskInfo& other)
        : vc{other.vc}, graph{other.graph}, tableEntry{other.tableEntry},
          propertiesToScan{other.propertiesToScan} {}

    bool hasPropertiesToScan() const { return !propertiesToScan.empty(); }
};

class VertexComputeTask : public common::Task {
public:
    VertexComputeTask(uint64_t maxNumThreads, const VertexComputeTaskInfo& info,
        std::shared_ptr<VertexComputeTaskSharedState> sharedState)
        : common::Task{maxNumThreads}, info{info}, sharedState{std::move(sharedState)} {};

    VertexComputeTaskSharedState* getSharedState() const { return sharedState.get(); }

    void run() override;

    void runSparse();

private:
    VertexComputeTaskInfo info;
    std::shared_ptr<VertexComputeTaskSharedState> sharedState;
};

} // namespace function
} // namespace lbug
