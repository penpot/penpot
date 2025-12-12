#pragma once

#include "density_state.h"
#include "gds_object_manager.h"
#include "graph/graph.h"

namespace lbug {
namespace storage {
class MemoryManager;
}
namespace processor {
struct ExecutionContext;
}
namespace function {

// TODO(Xiyang): optimize if edgeID is not needed.
class ParentList {
public:
    void setNbrInfo(common::nodeID_t nodeID_, common::relID_t edgeID_, bool isFwd_) {
        nodeID = nodeID_;
        edgeID = edgeID_;
        isFwd = isFwd_;
    }
    common::nodeID_t getNodeID() const { return nodeID; }
    common::relID_t getEdgeID() const { return edgeID; }
    bool isFwdEdge() const { return isFwd; }

    void setNextPtr(ParentList* ptr) { next.store(ptr, std::memory_order_relaxed); }
    ParentList* getNextPtr() { return next.load(std::memory_order_relaxed); }

    void setIter(uint16_t iter_) { iter = iter_; }
    uint16_t getIter() const { return iter; }

    void setCost(double cost_) { cost = cost_; }
    double getCost() const { return cost; }

private:
    common::nodeID_t nodeID;
    common::relID_t edgeID;
    bool isFwd = true;

    uint16_t iter = UINT16_MAX;
    double cost = std::numeric_limits<double>::max();
    // Next pointer
    std::atomic<ParentList*> next;
};

class BaseBFSGraph {
    friend class BFSGraphManager;

public:
    explicit BaseBFSGraph(storage::MemoryManager* mm) : mm{mm} {}
    virtual ~BaseBFSGraph() = default;

    // This function should be called by a worker thread Ti to grab a block of memory that
    // Ti owns and writes to.
    ObjectBlock<ParentList>* addNewBlock();

    virtual void pinTableID(common::table_id_t tableID) = 0;

    // Used to track path for all shortest path & variable length path.
    virtual void addParent(uint16_t iter, common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) = 0;
    // Used to track path for single shortest path. Assume each offset has at most one parent.
    virtual void addSingleParent(uint16_t iter, common::nodeID_t boundNodeID,
        common::relID_t edgeID, common::nodeID_t nbrNodeID, bool fwdEdge,
        ObjectBlock<ParentList>* block) = 0;
    // Used to track path for all weighted shortest path.
    virtual bool tryAddParentWithWeight(common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, double weight,
        ObjectBlock<ParentList>* block) = 0;
    // Used to track path for single weighted shortest path. Assume each offset has at most one
    // parent.
    virtual bool tryAddSingleParentWithWeight(common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, double weight,
        ObjectBlock<ParentList>* block) = 0;

    virtual ParentList* getParentListHead(common::offset_t offset) = 0;
    virtual ParentList* getParentListHead(common::nodeID_t nodeID) = 0;

    virtual void setParentList(common::offset_t offset, ParentList* parentList) = 0;

    template<class TARGET>
    TARGET& cast() {
        return common::ku_dynamic_cast<TARGET&>(*this);
    }

protected:
    std::mutex mtx;
    storage::MemoryManager* mm;
    std::vector<std::unique_ptr<ObjectBlock<ParentList>>> blocks;
};

class DenseBFSGraph : public BaseBFSGraph {
    friend class BFSGraphManager;
    friend class BFSGraphInitVertexCompute;

public:
    DenseBFSGraph(storage::MemoryManager* mm, common::table_id_map_t<common::offset_t> maxOffsetMap)
        : BaseBFSGraph{mm}, maxOffsetMap{std::move(maxOffsetMap)} {}

    void init(processor::ExecutionContext* context, graph::Graph* graph);

    void pinTableID(common::table_id_t tableID) override;

    void addParent(uint16_t iter, common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) override;
    void addSingleParent(uint16_t iter, common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) override;
    bool tryAddParentWithWeight(common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, double weight,
        ObjectBlock<ParentList>* block) override;
    bool tryAddSingleParentWithWeight(common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, double weight,
        ObjectBlock<ParentList>* block) override;

    ParentList* getParentListHead(common::offset_t offset) override;
    ParentList* getParentListHead(common::nodeID_t nodeID) override;

    void setParentList(common::offset_t offset, ParentList* parentList) override;

private:
    common::table_id_map_t<common::offset_t> maxOffsetMap;
    GDSDenseObjectManager<std::atomic<ParentList*>> denseObjects;
    std::atomic<ParentList*>* curData = nullptr;
};

class SparseBFSGraph : public BaseBFSGraph {
    friend class BFSGraphManager;

public:
    explicit SparseBFSGraph(storage::MemoryManager* mm,
        common::table_id_map_t<common::offset_t> maxOffsetMap)
        : BaseBFSGraph{mm}, sparseObjects{maxOffsetMap} {}

    void pinTableID(common::table_id_t tableID) override;

    void addParent(uint16_t iter, common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) override;
    void addSingleParent(uint16_t iter, common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, ObjectBlock<ParentList>* block) override;
    bool tryAddParentWithWeight(common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, double weight,
        ObjectBlock<ParentList>* block) override;
    bool tryAddSingleParentWithWeight(common::nodeID_t boundNodeID, common::relID_t edgeID,
        common::nodeID_t nbrNodeID, bool fwdEdge, double weight,
        ObjectBlock<ParentList>* block) override;

    ParentList* getParentListHead(common::offset_t offset) override;
    ParentList* getParentListHead(common::nodeID_t nodeID) override;

    void setParentList(common::offset_t offset, ParentList* parentList) override;

    const std::unordered_map<common::offset_t, ParentList*>& getCurrentData() const {
        return *curData;
    }

private:
    GDSSpareObjectManager<ParentList*> sparseObjects;
    std::unordered_map<common::offset_t, ParentList*>* curData = nullptr;
};

class BFSGraphManager {
public:
    BFSGraphManager(common::table_id_map_t<common::offset_t> maxOffsetMap,
        storage::MemoryManager* mm);

    BaseBFSGraph* getCurrentGraph() const {
        KU_ASSERT(curGraph);
        return curGraph;
    }

    void switchToDense(processor::ExecutionContext* context, graph::Graph* graph);

private:
    GDSDensityState state = GDSDensityState::SPARSE;
    std::unique_ptr<DenseBFSGraph> denseBFSGraph;
    std::unique_ptr<SparseBFSGraph> sparseBFSGraph;
    BaseBFSGraph* curGraph = nullptr;
};

} // namespace function
} // namespace lbug
