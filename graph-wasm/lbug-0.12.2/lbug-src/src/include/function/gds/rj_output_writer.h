#pragma once

#include "bfs_graph.h"
#include "common/counter.h"
#include "common/enums/path_semantic.h"
#include "common/mask.h"
#include "common/types/types.h"
#include "processor/result/factorized_table.h"

namespace lbug {
namespace function {

class RJOutputWriter {
public:
    RJOutputWriter(main::ClientContext* context, common::NodeOffsetMaskMap* outputNodeMask,
        common::nodeID_t sourceNodeID);
    virtual ~RJOutputWriter() = default;

    void beginWriting(common::table_id_t tableID) {
        pinOutputNodeMask(tableID);
        beginWritingInternal(tableID);
    }
    virtual void beginWritingInternal(common::table_id_t tableID) = 0;
    // Write
    virtual void write(processor::FactorizedTable& fTable, common::table_id_t tableID,
        common::LimitCounter* counter) = 0;
    virtual void write(processor::FactorizedTable& fTable, common::nodeID_t dstNodeID,
        common::LimitCounter* counter) = 0;

    bool inOutputNodeMask(common::offset_t offset);

    virtual std::unique_ptr<RJOutputWriter> copy() = 0;

protected:
    std::unique_ptr<common::ValueVector> createVector(const common::LogicalType& type);

    void pinOutputNodeMask(common::table_id_t tableID);

protected:
    main::ClientContext* context;
    common::NodeOffsetMaskMap* outputNodeMask;
    common::nodeID_t sourceNodeID_;

    std::vector<common::ValueVector*> vectors;
    std::unique_ptr<common::ValueVector> srcNodeIDVector;
    std::unique_ptr<common::ValueVector> dstNodeIDVector;
};

struct PathsOutputWriterInfo {
    // Semantic
    common::PathSemantic semantic = common::PathSemantic::WALK;
    // Range
    uint16_t lowerBound = 0;
    // Direction
    bool flipPath = false;
    bool writeEdgeDirection = false;
    bool writePath = false;
    // Node predicate mask
    common::NodeOffsetMaskMap* pathNodeMask = nullptr;

    bool hasNodeMask() const { return pathNodeMask != nullptr; }
};

class PathsOutputWriter : public RJOutputWriter {
public:
    PathsOutputWriter(main::ClientContext* context, common::NodeOffsetMaskMap* outputNodeMask,
        common::nodeID_t sourceNodeID, PathsOutputWriterInfo info, BaseBFSGraph& bfsGraph);

    void beginWritingInternal(common::table_id_t tableID) override { bfsGraph.pinTableID(tableID); }

    void write(processor::FactorizedTable& fTable, common::table_id_t tableID,
        common::LimitCounter* counter) override;
    void write(processor::FactorizedTable& fTable, common::nodeID_t dstNodeID,
        common::LimitCounter* counter) override;

protected:
    virtual void writeInternal(processor::FactorizedTable& fTable, common::nodeID_t dstNodeID,
        common::LimitCounter* counter) = 0;
    // Fast path when there is no node predicate or semantic check
    void dfsFast(ParentList* firstParent, processor::FactorizedTable& fTable,
        common::LimitCounter* counter);
    // Slow path to check node predicate or semantic.
    void dfsSlow(ParentList* firstParent, processor::FactorizedTable& fTable,
        common::LimitCounter* counter);

    bool updateCounterAndTerminate(common::LimitCounter* counter);

    ParentList* findFirstParent(common::offset_t dstOffset) const;

    bool checkPathNodeMask(ParentList* element) const;
    // Check semantics
    bool checkAppendSemantic(const std::vector<ParentList*>& path, ParentList* candidate) const;
    bool checkReplaceTopSemantic(const std::vector<ParentList*>& path, ParentList* candidate) const;
    bool isAppendTrail(const std::vector<ParentList*>& path, ParentList* candidate) const;
    bool isAppendAcyclic(const std::vector<ParentList*>& path, ParentList* candidate) const;
    bool isReplaceTopTrail(const std::vector<ParentList*>& path, ParentList* candidate) const;
    bool isReplaceTopAcyclic(const std::vector<ParentList*>& path, ParentList* candidate) const;

    bool isNextViable(ParentList* next, const std::vector<ParentList*>& path) const;

    void beginWritePath(common::idx_t length) const;
    void writePath(const std::vector<ParentList*>& path) const;
    void writePathFwd(const std::vector<ParentList*>& path) const;
    void writePathBwd(const std::vector<ParentList*>& path) const;

    void addEdge(common::relID_t edgeID, bool fwdEdge, common::sel_t pos) const;
    void addNode(common::nodeID_t nodeID, common::sel_t pos) const;

protected:
    PathsOutputWriterInfo info;
    BaseBFSGraph& bfsGraph;

    std::unique_ptr<common::ValueVector> directionVector = nullptr;
    std::unique_ptr<common::ValueVector> lengthVector = nullptr;
    std::unique_ptr<common::ValueVector> pathNodeIDsVector = nullptr;
    std::unique_ptr<common::ValueVector> pathEdgeIDsVector = nullptr;
};

class SPPathsOutputWriter : public PathsOutputWriter {
public:
    SPPathsOutputWriter(main::ClientContext* context, common::NodeOffsetMaskMap* outputNodeMask,
        common::nodeID_t sourceNodeID, PathsOutputWriterInfo info, BaseBFSGraph& bfsGraph)
        : PathsOutputWriter{context, outputNodeMask, sourceNodeID, info, bfsGraph} {}

    void writeInternal(processor::FactorizedTable& fTable, common::nodeID_t dstNodeID,
        common::LimitCounter* counter) override;

    std::unique_ptr<RJOutputWriter> copy() override {
        return std::make_unique<SPPathsOutputWriter>(context, outputNodeMask, sourceNodeID_, info,
            bfsGraph);
    }
};

} // namespace function
} // namespace lbug
