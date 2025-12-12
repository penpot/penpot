#pragma once

#include <atomic>
#include <mutex>

#include "compute.h"
#include "density_state.h"
#include "gds_object_manager.h"

namespace lbug {
namespace processor {
struct ExecutionContext;
}
namespace function {

using iteration_t = uint16_t;
static constexpr iteration_t FRONTIER_UNVISITED = UINT16_MAX;
static constexpr iteration_t FRONTIER_INITIAL_VISITED = 0;

// Base frontier implementation.
// A frontier keeps track of the existence of node. Instead of using boolean, we assign an iteration
// number to each node. A node with iteration number "i", meaning it is visited in the i-th
// iteration.
class LBUG_API Frontier {
public:
    virtual ~Frontier() = default;

    virtual void pinTableID(common::table_id_t tableID) = 0;

    virtual void addNode(common::nodeID_t nodeID, iteration_t iter) = 0;
    virtual void addNode(common::offset_t offset, iteration_t iter) = 0;
    virtual void addNodes(const std::vector<common::nodeID_t>& nodeIDs, iteration_t iter) = 0;

    virtual iteration_t getIteration(common::offset_t offset) const = 0;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }
};

// Sparse frontier implementation assuming the number of nodes is small.
// Use an STL hash map to maintain node offset-> iteration number
class LBUG_API SparseFrontier : public Frontier {
    friend class SparseFrontierReference;
    friend class SPFrontierPair;
    friend class DenseSparseDynamicFrontierPair;

public:
    explicit SparseFrontier(const common::table_id_map_t<common::offset_t>& nodeMaxOffsetMap)
        : sparseObjects{nodeMaxOffsetMap} {}

    void pinTableID(common::table_id_t tableID) override;

    void addNode(common::nodeID_t nodeID, iteration_t iter) override;
    void addNode(common::offset_t offset, iteration_t iter) override;
    void addNodes(const std::vector<common::nodeID_t>& nodeIDs, iteration_t iter) override;

    iteration_t getIteration(common::offset_t offset) const override;

    uint64_t size() const { return sparseObjects.size(); }

    const std::unordered_map<common::offset_t, iteration_t>& getCurrentData() const {
        return *curData;
    }

private:
    GDSSpareObjectManager<iteration_t> sparseObjects;
    std::unordered_map<common::offset_t, iteration_t>* curData = nullptr;
};

// Sparse frontier implementation that refers to the data owned by another sparse frontier.
// This should be used only for shortest-path type of algorithms where a node is guaranteed
// to be visited only once. See SPFrontierPair for its usage.
class SparseFrontierReference : public Frontier {
public:
    explicit SparseFrontierReference(SparseFrontier& frontier)
        : sparseObjects{frontier.sparseObjects} {}

    void pinTableID(common::table_id_t tableID) override;

    void addNode(common::nodeID_t nodeID, iteration_t iter) override;
    void addNode(common::offset_t offset, iteration_t iter) override;
    void addNodes(const std::vector<common::nodeID_t>& nodeIDs, iteration_t iter) override;

    iteration_t getIteration(common::offset_t offset) const override;

    const std::unordered_map<common::offset_t, iteration_t>& getCurrentData() const {
        return *curData;
    }

private:
    GDSSpareObjectManager<iteration_t>& sparseObjects;
    std::unordered_map<common::offset_t, iteration_t>* curData = nullptr;
};

// Dense frontier implementation assuming the number of nodes is large.
// Use an array of iteration number. The array is allocated to max offset
class LBUG_API DenseFrontier : public Frontier {
    friend class SparseFrontier;
    friend class DenseFrontierReference;
    friend class SPFrontierPair;
    friend class DenseSparseDynamicFrontierPair;

public:
    explicit DenseFrontier(const common::table_id_map_t<common::offset_t>& nodeMaxOffsetMap)
        : nodeMaxOffsetMap{nodeMaxOffsetMap} {}
    DenseFrontier(const DenseFrontier& other) = delete;
    DenseFrontier(const DenseFrontier&& other) = delete;

    // Allocate memory and initialize.
    void init(processor::ExecutionContext* context, graph::Graph* graph, iteration_t val);
    void resetValue(processor::ExecutionContext* context, graph::Graph* graph, iteration_t val);

    void pinTableID(common::table_id_t tableID) override;

    void addNode(common::nodeID_t nodeID, iteration_t iter) override;
    void addNode(common::offset_t offset, iteration_t iter) override;
    void addNodes(const std::vector<common::nodeID_t>& nodeIDs, iteration_t iter) override;

    iteration_t getIteration(common::offset_t offset) const override;

    // Get frontier without initialization.
    static std::unique_ptr<DenseFrontier> getUninitializedFrontier(
        processor::ExecutionContext* context, graph::Graph* graph);
    // Get frontier initialized to UNVISITED.
    static std::unique_ptr<DenseFrontier> getUnvisitedFrontier(processor::ExecutionContext* context,
        graph::Graph* graph);
    // Get frontier initialized to INITIAL_VISITED.
    static std::unique_ptr<DenseFrontier> getVisitedFrontier(processor::ExecutionContext* context,
        graph::Graph* graph);
    // Init frontier to 0 according to mask
    static std::unique_ptr<DenseFrontier> getVisitedFrontier(processor::ExecutionContext* context,
        graph::Graph* graph, common::NodeOffsetMaskMap* maskMap);

private:
    common::table_id_map_t<common::offset_t> nodeMaxOffsetMap;
    GDSDenseObjectManager<std::atomic<iteration_t>> denseObjects;
    std::atomic<iteration_t>* curData = nullptr;
};

// Dense frontier implementation that refers to the data owned by another dense frontier.
// Should be used in the same case as SparseFrontierReference
class DenseFrontierReference : public Frontier {
    friend class SPFrontierPair;

public:
    explicit DenseFrontierReference(const DenseFrontier& denseFrontier)
        : denseObjects{denseFrontier.denseObjects} {}

    void pinTableID(common::table_id_t tableID) override;

    void addNode(common::nodeID_t nodeID, iteration_t iter) override;
    void addNode(common::offset_t offset, iteration_t iter) override;
    void addNodes(const std::vector<common::nodeID_t>& nodeIDs, iteration_t iter) override;

    iteration_t getIteration(common::offset_t offset) const override;

private:
    const GDSDenseObjectManager<std::atomic<iteration_t>>& denseObjects;
    std::atomic<iteration_t>* curData = nullptr;
};

class LBUG_API FrontierPair {
public:
    FrontierPair() { hasActiveNodesForNextIter_.store(false); }
    virtual ~FrontierPair() = default;

    void resetCurrentIter() { curIter = 0; }
    iteration_t getCurrentIter() const { return curIter; }

    void setActiveNodesForNextIter() { hasActiveNodesForNextIter_.store(true); }

    bool continueNextIter(uint16_t maxIter) {
        return hasActiveNodesForNextIter_.load(std::memory_order_relaxed) &&
               getCurrentIter() < maxIter;
    }

    // Initialize state for new iteration.
    void beginNewIteration();
    void pinCurrentFrontier(common::table_id_t tableID);
    void pinNextFrontier(common::table_id_t tableID);
    // Pin current & next frontier
    void beginFrontierComputeBetweenTables(common::table_id_t curTableID,
        common::table_id_t nextTableID);

    // Write to next frontier
    void addNodeToNextFrontier(common::nodeID_t nodeID);
    void addNodeToNextFrontier(common::offset_t offset);
    void addNodesToNextFrontier(const std::vector<common::nodeID_t>& nodeIDs);

    iteration_t getNextFrontierValue(common::offset_t offset);
    bool isActiveOnCurrentFrontier(common::offset_t offset);
    virtual std::unordered_set<common::offset_t> getActiveNodesOnCurrentFrontier() = 0;

    virtual GDSDensityState getState() const = 0;
    virtual bool needSwitchToDense(uint64_t threshold) const = 0;
    virtual void switchToDense(processor::ExecutionContext* context, graph::Graph* graph) = 0;

    template<class TARGET>
    TARGET* ptrCast() {
        return common::ku_dynamic_cast<TARGET*>(this);
    }

protected:
    virtual void beginNewIterationInternalNoLock() = 0;

protected:
    std::mutex mtx;
    // curIter is the iteration number of the algorithm and starts from 0.
    iteration_t curIter = 0;
    std::atomic<bool> hasActiveNodesForNextIter_;
    Frontier* currentFrontier = nullptr;
    Frontier* nextFrontier = nullptr;
};

// Shortest path (excluding weighted shortest path )frontier implementation. Different from other
// recursive algorithms, shortest path has the guarantee that a node will not be visited repeatedly
// in different iteration. So we make current/next frontier reference writes to the same frontier.
class SPFrontierPair : public FrontierPair {
public:
    explicit SPFrontierPair(std::unique_ptr<DenseFrontier> denseFrontier);

    // Get sparse or dense frontier based on state.
    // No need to specify current or next because there is only one frontier.
    Frontier* getFrontier();

    void beginNewIterationInternalNoLock() override;

    // Get number of active nodes in current frontier. Used for shortest path early termination.
    common::offset_t getNumActiveNodesInCurrentFrontier(common::NodeOffsetMaskMap& mask);

    std::unordered_set<common::offset_t> getActiveNodesOnCurrentFrontier() override;

    GDSDensityState getState() const override { return state; }
    bool needSwitchToDense(uint64_t threshold) const override {
        return state == GDSDensityState::SPARSE && sparseFrontier->size() > threshold;
    }
    void switchToDense(processor::ExecutionContext* context, graph::Graph* graph) override;

private:
    GDSDensityState state;
    std::unique_ptr<DenseFrontier> denseFrontier;
    std::unique_ptr<DenseFrontierReference> curDenseFrontier = nullptr;
    std::unique_ptr<DenseFrontierReference> nextDenseFrontier = nullptr;
    std::unique_ptr<SparseFrontier> sparseFrontier;
    std::unique_ptr<SparseFrontierReference> curSparseFrontier = nullptr;
    std::unique_ptr<SparseFrontierReference> nextSparseFrontier = nullptr;
};

// Frontier pair implementation that switches from sparse to dense adaptively.
class LBUG_API DenseSparseDynamicFrontierPair : public FrontierPair {
public:
    DenseSparseDynamicFrontierPair(std::unique_ptr<DenseFrontier> curDenseFrontier,
        std::unique_ptr<DenseFrontier> nextDenseFrontier);

    void beginNewIterationInternalNoLock() override;

    std::unordered_set<common::offset_t> getActiveNodesOnCurrentFrontier() override;

    GDSDensityState getState() const override { return state; }
    bool needSwitchToDense(uint64_t threshold) const override {
        return state == GDSDensityState::SPARSE && nextSparseFrontier->size() > threshold;
    }
    void switchToDense(processor::ExecutionContext* context, graph::Graph* graph) override;

private:
    GDSDensityState state;
    std::unique_ptr<DenseFrontier> curDenseFrontier = nullptr;
    std::unique_ptr<DenseFrontier> nextDenseFrontier = nullptr;
    std::unique_ptr<SparseFrontier> curSparseFrontier = nullptr;
    std::unique_ptr<SparseFrontier> nextSparseFrontier = nullptr;
};

// Frontier pair implementation that only uses dense frontier. This is mostly used in
// algorithms like wcc, scc where algorithms touch all nodes in the graph.
class LBUG_API DenseFrontierPair : public FrontierPair {
public:
    DenseFrontierPair(std::unique_ptr<DenseFrontier> curDenseFrontier,
        std::unique_ptr<DenseFrontier> nextDenseFrontier);

    void beginNewIterationInternalNoLock() override;

    std::unordered_set<common::offset_t> getActiveNodesOnCurrentFrontier() override {
        KU_UNREACHABLE;
    }

    void resetValue(processor::ExecutionContext* context, graph::Graph* graph, iteration_t val);

    GDSDensityState getState() const override { return GDSDensityState::DENSE; }
    bool needSwitchToDense(uint64_t) const override { return false; }
    void switchToDense(processor::ExecutionContext*, graph::Graph*) override {
        // Do nothing.
    }

private:
    std::shared_ptr<DenseFrontier> curDenseFrontier;
    std::shared_ptr<DenseFrontier> nextDenseFrontier;
};

class SPEdgeCompute : public EdgeCompute {
public:
    explicit SPEdgeCompute(SPFrontierPair* frontierPair)
        : frontierPair{frontierPair}, numNodesReached{0} {}

    void resetSingleThreadState() override { numNodesReached = 0; }

    bool terminate(common::NodeOffsetMaskMap& maskMap) override;

protected:
    SPFrontierPair* frontierPair;
    // States that should be only modified with single thread
    common::offset_t numNodesReached;
};

} // namespace function
} // namespace lbug
