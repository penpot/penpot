#include "function/gds/bfs_graph.h"

#include "function/gds/gds_utils.h"
#include "processor/execution_context.h"

using namespace lbug::common;
using namespace lbug::graph;
using namespace lbug::processor;

namespace lbug {
namespace function {

static constexpr uint64_t BFS_GRAPH_BLOCK_SIZE = (std::uint64_t)1 << 19;

ObjectBlock<ParentList>* BaseBFSGraph::addNewBlock() {
    std::unique_lock lck{mtx};
    auto memBlock = mm->allocateBuffer(false /* init to 0 */, BFS_GRAPH_BLOCK_SIZE);
    blocks.push_back(
        std::make_unique<ObjectBlock<ParentList>>(std::move(memBlock), BFS_GRAPH_BLOCK_SIZE));
    return blocks[blocks.size() - 1].get();
}

class BFSGraphInitVertexCompute : public VertexCompute {
public:
    explicit BFSGraphInitVertexCompute(DenseBFSGraph& bfsGraph) : bfsGraph{bfsGraph} {}

    bool beginOnTable(table_id_t tableID) override {
        bfsGraph.pinTableID(tableID);
        return true;
    }

    void vertexCompute(offset_t startOffset, offset_t endOffset, table_id_t) override {
        for (auto i = startOffset; i < endOffset; ++i) {
            bfsGraph.curData[i].store(nullptr);
        }
    }

    std::unique_ptr<VertexCompute> copy() override {
        return std::make_unique<BFSGraphInitVertexCompute>(bfsGraph);
    }

private:
    DenseBFSGraph& bfsGraph;
};

void DenseBFSGraph::init(ExecutionContext* context, Graph* graph) {
    auto mm = storage::MemoryManager::Get(*context->clientContext);
    for (auto& [tableID, maxOffset] : maxOffsetMap) {
        denseObjects.allocate(tableID, maxOffset, mm);
    }
    auto vc = std::make_unique<BFSGraphInitVertexCompute>(*this);
    GDSUtils::runVertexCompute(context, GDSDensityState::DENSE, graph, *vc);
}

void DenseBFSGraph::pinTableID(table_id_t tableID) {
    curData = denseObjects.getData(tableID);
}

static ParentList* reserveParent(nodeID_t boundNodeID, relID_t edgeID, bool fwdEdge,
    ObjectBlock<ParentList>* block) {
    auto parent = block->reserveNext();
    parent->setNbrInfo(boundNodeID, edgeID, fwdEdge);
    return parent;
}

void DenseBFSGraph::addParent(uint16_t iter, nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) {
    auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
    parent->setIter(iter);
    // Since by default the parentPtr of each node is nullptr, that's what we start with.
    ParentList* expected = nullptr;
    while (!curData[nbrNodeID.offset].compare_exchange_strong(expected, parent)) {}
    parent->setNextPtr(expected);
}

void DenseBFSGraph::addSingleParent(uint16_t iter, nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) {
    auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
    parent->setIter(iter);
    ParentList* expected = nullptr;
    if (curData[nbrNodeID.offset].compare_exchange_strong(expected, parent)) {
        parent->setNextPtr(expected);
    } else {
        // Other thread has added the parent. Do NOT add parent and revert reserved slot.
        block->revertLast();
    }
}

static double getCost(ParentList* parentList) {
    return parentList == nullptr ? std::numeric_limits<double>::max() : parentList->getCost();
}

bool DenseBFSGraph::tryAddParentWithWeight(nodeID_t boundNodeID, relID_t edgeID, nodeID_t nbrNodeID,
    bool fwdEdge, double weight, ObjectBlock<ParentList>* block) {
    ParentList* expected = getParentListHead(nbrNodeID.offset);
    auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
    parent->setCost(getParentListHead(boundNodeID)->getCost() + weight);
    while (true) {
        if (parent->getCost() < getCost(expected)) {
            // New parent has smaller cost, erase all existing parents and add new parent.
            if (curData[nbrNodeID.offset].compare_exchange_strong(expected, parent)) {
                parent->setNextPtr(nullptr);
                return true;
            }
        } else if (parent->getCost() == getCost(expected) && expected->getEdgeID() != edgeID) {
            // New parent has the same cost and comes from different edge,
            // append new parent as after existing parents.
            if (curData[nbrNodeID.offset].compare_exchange_strong(expected, parent)) {
                parent->setNextPtr(expected);
                return true;
            }
        } else {
            block->revertLast();
            return false;
        }
    }
}

bool DenseBFSGraph::tryAddSingleParentWithWeight(nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, double weight, ObjectBlock<ParentList>* block) {
    ParentList* expected = getParentListHead(nbrNodeID.offset);
    auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
    parent->setCost(getParentListHead(boundNodeID)->getCost() + weight);
    while (parent->getCost() < getCost(expected)) {
        if (curData[nbrNodeID.offset].compare_exchange_strong(expected, parent)) {
            // Since each node can have one parent, set next ptr to nullptr.
            parent->setNextPtr(nullptr);
            return true;
        }
    }
    // Other thread has added the parent. Do NOT add parent and revert reserved slot.
    block->revertLast();
    return false;
}

ParentList* DenseBFSGraph::getParentListHead(offset_t offset) {
    KU_ASSERT(curData);
    return curData[offset].load(std::memory_order_relaxed);
}

ParentList* DenseBFSGraph::getParentListHead(nodeID_t nodeID) {
    return denseObjects.getData(nodeID.tableID)[nodeID.offset].load(std::memory_order_relaxed);
}

void DenseBFSGraph::setParentList(offset_t offset, ParentList* parentList) {
    KU_ASSERT(curData && getParentListHead(offset) == nullptr);
    curData[offset].store(parentList, std::memory_order_relaxed);
}

void SparseBFSGraph::pinTableID(table_id_t tableID) {
    curData = sparseObjects.getData(tableID);
}

void SparseBFSGraph::addParent(uint16_t iter, nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) {
    auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
    parent->setIter(iter);
    if (curData->contains(nbrNodeID.offset)) {
        parent->setNextPtr(curData->at(nbrNodeID.offset));
        curData->at(nbrNodeID.offset) = parent;
    } else {
        parent->setNextPtr(nullptr);
        curData->insert({nbrNodeID.offset, parent});
    }
}

void SparseBFSGraph::addSingleParent(uint16_t iter, nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) {
    if (curData->contains(nbrNodeID.offset)) {
        return;
    }
    auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
    parent->setIter(iter);
    parent->setNextPtr(nullptr);
    curData->insert({nbrNodeID.offset, parent});
}

bool SparseBFSGraph::tryAddParentWithWeight(nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, double weight, ObjectBlock<ParentList>* block) {
    auto nbrParent = getParentListHead(nbrNodeID.offset);
    auto nbrCost = getCost(nbrParent);
    auto newCost = getParentListHead(boundNodeID)->getCost() + weight;
    if (newCost < nbrCost) {
        auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
        parent->setCost(newCost);
        parent->setNextPtr(nullptr);
        curData->erase(nbrNodeID.offset);
        curData->insert({nbrNodeID.offset, parent});
        return true;
    }
    // Append parent if newCost is the same as old cost. And the newCost comes from a different edge
    // Otherwise, for cases like A->B->C, A->D->C, C->E. If ABD and ADC has the same cost, we will
    // visit twice to E with the same cost and same edge.
    if (newCost == nbrCost && nbrParent->getEdgeID() != edgeID) {
        auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
        parent->setCost(newCost);
        if (curData->contains(nbrNodeID.offset)) {
            parent->setNextPtr(curData->at(nbrNodeID.offset));
            curData->erase(nbrNodeID.offset);
        } else {
            parent->setNextPtr(nullptr);
        }
        curData->insert({nbrNodeID.offset, parent});
        return true;
    }
    return false;
}

bool SparseBFSGraph::tryAddSingleParentWithWeight(nodeID_t boundNodeID, relID_t edgeID,
    nodeID_t nbrNodeID, bool fwdEdge, double weight, ObjectBlock<ParentList>* block) {
    auto nbrCost = getCost(getParentListHead(nbrNodeID.offset));
    auto newCost = getParentListHead(boundNodeID)->getCost() + weight;
    if (newCost < nbrCost) {
        auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
        parent->setCost(newCost);
        parent->setNextPtr(nullptr);
        curData->erase(nbrNodeID.offset);
        curData->insert({nbrNodeID.offset, parent});
        return true;
    }
    if (newCost == nbrCost) {
        if (curData->contains(nbrNodeID.offset)) {
            return false;
        }
        auto parent = reserveParent(boundNodeID, edgeID, fwdEdge, block);
        parent->setCost(newCost);
        parent->setNextPtr(nullptr);
        curData->insert({nbrNodeID.offset, parent});
    }
    return false;
}

ParentList* SparseBFSGraph::getParentListHead(offset_t offset) {
    KU_ASSERT(curData);
    if (!curData->contains(offset)) {
        return nullptr;
    }
    return curData->at(offset);
}

ParentList* SparseBFSGraph::getParentListHead(nodeID_t nodeID) {
    auto data = sparseObjects.getData(nodeID.tableID);
    if (!data->contains(nodeID.offset)) {
        return nullptr;
    }
    return data->at(nodeID.offset);
}

void SparseBFSGraph::setParentList(offset_t offset, ParentList* parentList) {
    KU_ASSERT(!curData->contains(offset));
    curData->insert({offset, parentList});
}

BFSGraphManager::BFSGraphManager(table_id_map_t<offset_t> maxOffsetMap,
    storage::MemoryManager* mm) {
    denseBFSGraph = std::make_unique<DenseBFSGraph>(mm, maxOffsetMap);
    sparseBFSGraph = std::make_unique<SparseBFSGraph>(mm, maxOffsetMap);
    curGraph = sparseBFSGraph.get();
}

void BFSGraphManager::switchToDense(ExecutionContext* context, Graph* graph) {
    KU_ASSERT(state == GDSDensityState::SPARSE);
    state = GDSDensityState::DENSE;
    denseBFSGraph->init(context, graph);
    denseBFSGraph->blocks = std::move(sparseBFSGraph->blocks);
    for (auto& [tableID, map] : sparseBFSGraph->sparseObjects.getData()) {
        denseBFSGraph->pinTableID(tableID);
        for (auto& [offset, ptr] : map) {
            denseBFSGraph->setParentList(offset, ptr);
        }
    }
    curGraph = denseBFSGraph.get();
}

} // namespace function
} // namespace lbug
