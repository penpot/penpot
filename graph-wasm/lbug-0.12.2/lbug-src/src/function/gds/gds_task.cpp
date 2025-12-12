#include "function/gds/gds_task.h"

#include "catalog/catalog_entry/rel_group_catalog_entry.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "function/gds/frontier_morsel.h"
#include "graph/graph.h"

using namespace lbug::common;

namespace lbug {
namespace function {

table_id_t FrontierTaskInfo::getBoundTableID() const {
    switch (direction) {
    case ExtendDirection::FWD:
        return srcTableID;
    case ExtendDirection::BWD:
        return dstTableID;
    default:
        KU_UNREACHABLE;
    }
}

table_id_t FrontierTaskInfo::getNbrTableID() const {
    switch (direction) {
    case ExtendDirection::FWD:
        return dstTableID;
    case ExtendDirection::BWD:
        return srcTableID;
    default:
        KU_UNREACHABLE;
    }
}

oid_t FrontierTaskInfo::getRelTableID() const {
    return relGroupEntry->constCast<catalog::RelGroupCatalogEntry>()
        .getRelEntryInfo(srcTableID, dstTableID)
        ->oid;
}

void FrontierTask::run() {
    FrontierMorsel morsel;
    auto numActiveNodes = 0u;
    auto graph = info.graph;
    auto scanState = graph->prepareRelScan(*info.relGroupEntry, info.getRelTableID(),
        info.getNbrTableID(), info.propertiesToScan);
    auto ec = info.edgeCompute.copy();
    auto boundTableID = info.getBoundTableID();
    switch (info.direction) {
    case ExtendDirection::FWD: {
        while (sharedState->morselDispatcher.getNextRangeMorsel(morsel)) {
            for (auto offset = morsel.getBeginOffset(); offset < morsel.getEndOffset(); ++offset) {
                if (!sharedState->frontierPair.isActiveOnCurrentFrontier(offset)) {
                    continue;
                }
                nodeID_t nodeID = {offset, boundTableID};
                for (auto chunk : graph->scanFwd(nodeID, *scanState)) {
                    auto activeNodes = ec->edgeCompute(nodeID, chunk, true);
                    sharedState->frontierPair.addNodesToNextFrontier(activeNodes);
                    numActiveNodes += activeNodes.size();
                }
            }
        }
    } break;
    case ExtendDirection::BWD: {
        while (sharedState->morselDispatcher.getNextRangeMorsel(morsel)) {
            for (auto offset = morsel.getBeginOffset(); offset < morsel.getEndOffset(); ++offset) {
                if (!sharedState->frontierPair.isActiveOnCurrentFrontier(offset)) {
                    continue;
                }
                nodeID_t nodeID = {offset, boundTableID};
                for (auto chunk : graph->scanBwd(nodeID, *scanState)) {
                    auto activeNodes = ec->edgeCompute(nodeID, chunk, false);
                    sharedState->frontierPair.addNodesToNextFrontier(activeNodes);
                    numActiveNodes += activeNodes.size();
                }
            }
        }
    } break;
    default:
        KU_UNREACHABLE;
    }
    if (numActiveNodes) {
        sharedState->frontierPair.setActiveNodesForNextIter();
    }
}

void FrontierTask::runSparse() {
    auto numActiveNodes = 0u;
    auto graph = info.graph;
    auto scanState = graph->prepareRelScan(*info.relGroupEntry, info.getRelTableID(),
        info.getNbrTableID(), info.propertiesToScan);
    auto ec = info.edgeCompute.copy();
    auto boundTableID = info.getBoundTableID();
    switch (info.direction) {
    case ExtendDirection::FWD: {
        for (const auto offset : sharedState->frontierPair.getActiveNodesOnCurrentFrontier()) {
            auto nodeID = nodeID_t{offset, boundTableID};
            for (auto chunk : graph->scanFwd(nodeID, *scanState)) {
                auto activeNodes = ec->edgeCompute(nodeID, chunk, true);
                sharedState->frontierPair.addNodesToNextFrontier(activeNodes);
                numActiveNodes += activeNodes.size();
            }
        }
    } break;
    case ExtendDirection::BWD: {
        for (auto& offset : sharedState->frontierPair.getActiveNodesOnCurrentFrontier()) {
            auto nodeID = nodeID_t{offset, boundTableID};
            for (auto chunk : graph->scanBwd(nodeID, *scanState)) {
                auto activeNodes = ec->edgeCompute(nodeID, chunk, false);
                sharedState->frontierPair.addNodesToNextFrontier(activeNodes);
                numActiveNodes += activeNodes.size();
            }
        }
    } break;
    default:
        KU_UNREACHABLE;
    }
    if (numActiveNodes) {
        sharedState->frontierPair.setActiveNodesForNextIter();
    }
}

void VertexComputeTask::run() {
    FrontierMorsel morsel;
    auto graph = info.graph;
    auto localVc = info.vc.copy();
    if (info.hasPropertiesToScan()) {
        auto scanState = graph->prepareVertexScan(info.tableEntry, info.propertiesToScan);
        while (sharedState->morselDispatcher.getNextRangeMorsel(morsel)) {
            for (auto chunk :
                graph->scanVertices(morsel.getBeginOffset(), morsel.getEndOffset(), *scanState)) {
                localVc->vertexCompute(chunk);
            }
        }
    } else {
        while (sharedState->morselDispatcher.getNextRangeMorsel(morsel)) {
            localVc->vertexCompute(morsel.getBeginOffset(), morsel.getEndOffset(),
                info.tableEntry->getTableID());
        }
    }
}

void VertexComputeTask::runSparse() {
    KU_ASSERT(!info.hasPropertiesToScan());
    auto localVc = info.vc.copy();
    localVc->vertexCompute(info.tableEntry->getTableID());
}

} // namespace function
} // namespace lbug
