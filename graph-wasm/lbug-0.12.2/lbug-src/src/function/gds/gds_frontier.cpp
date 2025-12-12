#include "function/gds/gds_frontier.h"

#include "function/gds/gds_utils.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::graph;
using namespace lbug::processor;

namespace lbug {
namespace function {

void SparseFrontier::pinTableID(table_id_t tableID) {
    curData = sparseObjects.getData(tableID);
}

void SparseFrontier::addNode(nodeID_t nodeID, iteration_t iter) {
    KU_ASSERT(curData);
    addNode(nodeID.offset, iter);
}

void SparseFrontier::addNode(offset_t offset, iteration_t iter) {
    KU_ASSERT(curData);
    if (!curData->contains(offset)) {
        curData->insert({offset, iter});
    } else {
        curData->at(offset) = iter;
    }
}

void SparseFrontier::addNodes(const std::vector<nodeID_t>& nodeIDs, iteration_t iter) {
    KU_ASSERT(curData);
    for (auto& nodeID : nodeIDs) {
        addNode(nodeID.offset, iter);
    }
}

iteration_t SparseFrontier::getIteration(offset_t offset) const {
    KU_ASSERT(curData);
    if (!curData->contains(offset)) {
        return FRONTIER_UNVISITED;
    }
    return curData->at(offset);
}

void SparseFrontierReference::pinTableID(table_id_t tableID) {
    curData = sparseObjects.getData(tableID);
}

void SparseFrontierReference::addNode(offset_t offset, iteration_t iter) {
    KU_ASSERT(curData);
    if (!curData->contains(offset)) {
        curData->insert({offset, iter});
    } else {
        curData->at(offset) = iter;
    }
}

void SparseFrontierReference::addNode(nodeID_t nodeID, iteration_t iter) {
    KU_ASSERT(curData);
    addNode(nodeID.offset, iter);
}

void SparseFrontierReference::addNodes(const std::vector<nodeID_t>& nodeIDs, iteration_t iter) {
    KU_ASSERT(curData);
    for (auto nodeID : nodeIDs) {
        addNode(nodeID.offset, iter);
    }
}

iteration_t SparseFrontierReference::getIteration(offset_t offset) const {
    KU_ASSERT(curData);
    if (!curData->contains(offset)) {
        return FRONTIER_UNVISITED;
    }
    return curData->at(offset);
}

class DenseFrontierInitVertexCompute : public VertexCompute {
public:
    DenseFrontierInitVertexCompute(DenseFrontier& frontier, iteration_t val)
        : frontier{frontier}, val{val} {}

    bool beginOnTable(table_id_t tableID) override {
        frontier.pinTableID(tableID);
        return true;
    }

    void vertexCompute(offset_t startOffset, offset_t endOffset, table_id_t) override {
        for (auto i = startOffset; i < endOffset; ++i) {
            frontier.addNode(i, val);
        }
    }

    std::unique_ptr<VertexCompute> copy() override {
        return std::make_unique<DenseFrontierInitVertexCompute>(frontier, val);
    }

private:
    DenseFrontier& frontier;
    iteration_t val;
};

void DenseFrontier::init(ExecutionContext* context, Graph* graph, iteration_t val) {
    auto mm = storage::MemoryManager::Get(*context->clientContext);
    for (const auto& [tableID, maxOffset] : nodeMaxOffsetMap) {
        denseObjects.allocate(tableID, maxOffset, mm);
    }
    resetValue(context, graph, val);
}

void DenseFrontier::resetValue(ExecutionContext* context, Graph* graph, iteration_t val) {
    auto vc = DenseFrontierInitVertexCompute(*this, val);
    GDSUtils::runVertexCompute(context, GDSDensityState::DENSE, graph, vc);
}

void DenseFrontier::pinTableID(table_id_t tableID) {
    curData = denseObjects.getData(tableID);
}

void DenseFrontier::addNode(nodeID_t nodeID, iteration_t iter) {
    KU_ASSERT(curData);
    curData[nodeID.offset].store(iter, std::memory_order_relaxed);
}

void DenseFrontier::addNode(offset_t offset, iteration_t iter) {
    KU_ASSERT(curData);
    curData[offset].store(iter, std::memory_order_relaxed);
}

void DenseFrontier::addNodes(const std::vector<nodeID_t>& nodeIDs, iteration_t iter) {
    KU_ASSERT(curData);
    for (auto nodeID : nodeIDs) {
        curData[nodeID.offset].store(iter, std::memory_order_relaxed);
    }
}

iteration_t DenseFrontier::getIteration(offset_t offset) const {
    KU_ASSERT(curData);
    return curData[offset].load(std::memory_order_relaxed);
}

std::unique_ptr<DenseFrontier> DenseFrontier::getUninitializedFrontier(ExecutionContext* context,
    Graph* graph) {
    auto transaction = transaction::Transaction::Get(*context->clientContext);
    return std::make_unique<DenseFrontier>(graph->getMaxOffsetMap(transaction));
}

std::unique_ptr<DenseFrontier> DenseFrontier::getUnvisitedFrontier(ExecutionContext* context,
    Graph* graph) {
    auto transaction = transaction::Transaction::Get(*context->clientContext);
    auto frontier = std::make_unique<DenseFrontier>(graph->getMaxOffsetMap(transaction));
    frontier->init(context, graph, FRONTIER_UNVISITED);
    return frontier;
}

std::unique_ptr<DenseFrontier> DenseFrontier::getVisitedFrontier(ExecutionContext* context,
    Graph* graph) {
    auto transaction = transaction::Transaction::Get(*context->clientContext);
    auto frontier = std::make_unique<DenseFrontier>(graph->getMaxOffsetMap(transaction));
    frontier->init(context, graph, FRONTIER_INITIAL_VISITED);
    return frontier;
}

std::unique_ptr<DenseFrontier> DenseFrontier::getVisitedFrontier(ExecutionContext* context,
    Graph* graph, NodeOffsetMaskMap* maskMap) {
    if (maskMap == nullptr) {
        return getVisitedFrontier(context, graph);
    }
    auto transaction = transaction::Transaction::Get(*context->clientContext);
    auto frontier = std::make_unique<DenseFrontier>(graph->getMaxOffsetMap(transaction));
    frontier->init(context, graph, FRONTIER_INITIAL_VISITED);
    for (auto [tableID, numNodes] : graph->getMaxOffsetMap(transaction)) {
        frontier->pinTableID(tableID);
        if (maskMap->containsTableID(tableID)) {
            auto mask = maskMap->getOffsetMask(tableID);
            for (auto i = 0u; i < numNodes; ++i) {
                if (!mask->isMasked(i)) {
                    frontier->curData[i].store(FRONTIER_UNVISITED);
                }
            }
        }
    }
    return frontier;
}

void DenseFrontierReference::pinTableID(table_id_t tableID) {
    curData = denseObjects.getData(tableID);
}

void DenseFrontierReference::addNode(nodeID_t nodeID, iteration_t iter) {
    KU_ASSERT(curData);
    curData[nodeID.offset].store(iter, std::memory_order_relaxed);
}

void DenseFrontierReference::addNode(offset_t offset, iteration_t iter) {
    KU_ASSERT(curData);
    curData[offset].store(iter, std::memory_order_relaxed);
}

void DenseFrontierReference::addNodes(const std::vector<nodeID_t>& nodeIDs, iteration_t iter) {
    KU_ASSERT(curData);
    for (auto nodeID : nodeIDs) {
        curData[nodeID.offset].store(iter, std::memory_order_relaxed);
    }
}

iteration_t DenseFrontierReference::getIteration(offset_t offset) const {
    KU_ASSERT(curData);
    return curData[offset].load(std::memory_order_relaxed);
}

void FrontierPair::beginNewIteration() {
    std::unique_lock<std::mutex> lck{mtx};
    curIter++;
    hasActiveNodesForNextIter_.store(false);
    beginNewIterationInternalNoLock();
}

void FrontierPair::beginFrontierComputeBetweenTables(table_id_t curTableID,
    table_id_t nextTableID) {
    pinCurrentFrontier(curTableID);
    pinNextFrontier(nextTableID);
}

void FrontierPair::pinCurrentFrontier(table_id_t tableID) {
    currentFrontier->pinTableID(tableID);
}

void FrontierPair::pinNextFrontier(table_id_t tableID) {
    nextFrontier->pinTableID(tableID);
}

void FrontierPair::addNodeToNextFrontier(nodeID_t nodeID) {
    nextFrontier->addNode(nodeID, curIter);
}

void FrontierPair::addNodeToNextFrontier(offset_t offset) {
    nextFrontier->addNode(offset, curIter);
}

void FrontierPair::addNodesToNextFrontier(const std::vector<nodeID_t>& nodeIDs) {
    nextFrontier->addNodes(nodeIDs, curIter);
}

iteration_t FrontierPair::getNextFrontierValue(offset_t offset) {
    return nextFrontier->getIteration(offset);
}

bool FrontierPair::isActiveOnCurrentFrontier(offset_t offset) {
    return currentFrontier->getIteration(offset) == curIter - 1;
}

Frontier* SPFrontierPair::getFrontier() {
    switch (state) {
    case GDSDensityState::SPARSE: {
        return sparseFrontier.get();
    }
    case GDSDensityState::DENSE: {
        return denseFrontier.get();
    }
    default:
        KU_UNREACHABLE;
    }
}

SPFrontierPair::SPFrontierPair(std::unique_ptr<DenseFrontier> denseFrontier)
    : state{GDSDensityState::SPARSE}, denseFrontier{std::move(denseFrontier)} {
    curDenseFrontier = std::make_unique<DenseFrontierReference>(*this->denseFrontier);
    nextDenseFrontier = std::make_unique<DenseFrontierReference>(*this->denseFrontier);
    sparseFrontier = std::make_unique<SparseFrontier>(this->denseFrontier->nodeMaxOffsetMap);
    curSparseFrontier = std::make_unique<SparseFrontierReference>(*this->sparseFrontier);
    nextSparseFrontier = std::make_unique<SparseFrontierReference>(*this->sparseFrontier);
    currentFrontier = curSparseFrontier.get();
    nextFrontier = nextSparseFrontier.get();
}

void SPFrontierPair::beginNewIterationInternalNoLock() {
    switch (state) {
    case GDSDensityState::SPARSE: {
        std::swap(curSparseFrontier, nextSparseFrontier);
        currentFrontier = curSparseFrontier.get();
        nextFrontier = nextSparseFrontier.get();
    } break;
    case GDSDensityState::DENSE: {
        std::swap(curDenseFrontier, nextDenseFrontier);
        currentFrontier = curDenseFrontier.get();
        nextFrontier = nextDenseFrontier.get();
    } break;
    default:
        KU_UNREACHABLE;
    }
}

offset_t SPFrontierPair::getNumActiveNodesInCurrentFrontier(NodeOffsetMaskMap& mask) {
    auto result = 0u;
    for (auto& [tableID, maxNumNodes] : denseFrontier->nodeMaxOffsetMap) {
        currentFrontier->pinTableID(tableID);
        if (!mask.containsTableID(tableID)) {
            continue;
        }
        auto offsetMask = mask.getOffsetMask(tableID);
        for (auto offset = 0u; offset < maxNumNodes; ++offset) {
            if (isActiveOnCurrentFrontier(offset)) {
                result += offsetMask->isMasked(offset);
            }
        }
    }
    return result;
}

std::unordered_set<offset_t> SPFrontierPair::getActiveNodesOnCurrentFrontier() {
    KU_ASSERT(state == GDSDensityState::SPARSE);
    std::unordered_set<offset_t> result;
    for (auto& [offset, iter] : curSparseFrontier->getCurrentData()) {
        if (iter != curIter - 1) {
            continue;
        }
        result.insert(offset);
    }
    return result;
}

void SPFrontierPair::switchToDense(ExecutionContext* context, graph::Graph* graph) {
    KU_ASSERT(state == GDSDensityState::SPARSE);
    state = GDSDensityState::DENSE;
    denseFrontier->init(context, graph, FRONTIER_UNVISITED);
    for (auto& [tableID, map] : sparseFrontier->sparseObjects.getData()) {
        nextDenseFrontier->pinTableID(tableID);
        for (auto [offset, iter] : map) {
            nextDenseFrontier->curData[offset].store(iter);
        }
    }
}

DenseSparseDynamicFrontierPair::DenseSparseDynamicFrontierPair(
    std::unique_ptr<DenseFrontier> curDenseFrontier,
    std::unique_ptr<DenseFrontier> nextDenseFrontier)
    : state{GDSDensityState::SPARSE}, curDenseFrontier{std::move(curDenseFrontier)},
      nextDenseFrontier{std::move(nextDenseFrontier)} {
    curSparseFrontier = std::make_unique<SparseFrontier>(this->curDenseFrontier->nodeMaxOffsetMap);
    nextSparseFrontier =
        std::make_unique<SparseFrontier>(this->nextDenseFrontier->nodeMaxOffsetMap);
    currentFrontier = curSparseFrontier.get();
    nextFrontier = nextSparseFrontier.get();
}

void DenseSparseDynamicFrontierPair::beginNewIterationInternalNoLock() {
    switch (state) {
    case GDSDensityState::SPARSE: {
        std::swap(curSparseFrontier, nextSparseFrontier);
        currentFrontier = curSparseFrontier.get();
        nextFrontier = nextSparseFrontier.get();
    } break;
    case GDSDensityState::DENSE: {
        std::swap(curDenseFrontier, nextDenseFrontier);
        currentFrontier = curDenseFrontier.get();
        nextFrontier = nextDenseFrontier.get();
    } break;
    default:
        KU_UNREACHABLE;
    }
}

std::unordered_set<offset_t> DenseSparseDynamicFrontierPair::getActiveNodesOnCurrentFrontier() {
    KU_ASSERT(state == GDSDensityState::SPARSE);
    std::unordered_set<offset_t> result;
    for (auto& [offset, iter] : *curSparseFrontier->curData) {
        if (iter != curIter - 1) {
            continue;
        }
        result.insert(offset);
    }
    return result;
}

void DenseSparseDynamicFrontierPair::switchToDense(ExecutionContext* context, Graph* graph) {
    KU_ASSERT(state == GDSDensityState::SPARSE);
    state = GDSDensityState::DENSE;
    curDenseFrontier->init(context, graph, FRONTIER_UNVISITED);
    nextDenseFrontier->init(context, graph, FRONTIER_UNVISITED);
    for (auto& [tableID, map] : nextSparseFrontier->sparseObjects.getData()) {
        nextDenseFrontier->pinTableID(tableID);
        for (auto [offset, iter] : map) {
            nextDenseFrontier->curData[offset].store(iter);
        }
    }
}

DenseFrontierPair::DenseFrontierPair(std::unique_ptr<DenseFrontier> curDenseFrontier,
    std::unique_ptr<DenseFrontier> nextDenseFrontier)
    : curDenseFrontier{std::move(curDenseFrontier)},
      nextDenseFrontier{std::move(nextDenseFrontier)} {
    currentFrontier = this->curDenseFrontier.get();
    nextFrontier = this->nextDenseFrontier.get();
}

void DenseFrontierPair::beginNewIterationInternalNoLock() {
    std::swap(curDenseFrontier, nextDenseFrontier);
    currentFrontier = curDenseFrontier.get();
    nextFrontier = nextDenseFrontier.get();
}

void DenseFrontierPair::resetValue(ExecutionContext* context, Graph* graph, iteration_t val) {
    curDenseFrontier->resetValue(context, graph, val);
    nextDenseFrontier->resetValue(context, graph, val);
}

static constexpr uint64_t EARLY_TERM_NUM_NODES_THRESHOLD = 100;

bool SPEdgeCompute::terminate(NodeOffsetMaskMap& maskMap) {
    auto targetNumNodes = maskMap.getNumMaskedNode();
    if (targetNumNodes > EARLY_TERM_NUM_NODES_THRESHOLD) {
        // Skip checking if it's unlikely to early terminate.
        return false;
    }
    numNodesReached += frontierPair->getNumActiveNodesInCurrentFrontier(maskMap);
    return numNodesReached == targetNumNodes;
}

} // namespace function
} // namespace lbug
