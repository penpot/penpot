#pragma once

#include "common/mask.h"
#include "common/types/types.h"
#include "graph/graph.h"

namespace lbug {
namespace function {

/**
 * Base interface for algorithms that can be implemented in Pregel-like vertex-centric manner or
 * more specifically Ligra's edgeCompute (called edgeUpdate in Ligra paper) function. Intended to be
 * passed to the helper functions in GDSUtils that parallelize such Pregel-like computations.
 */
class EdgeCompute {
public:
    virtual ~EdgeCompute() = default;

    // Does any work that is needed while extending the (boundNodeID, nbrNodeID, edgeID) edge.
    // boundNodeID is the nodeID that is in the current frontier and currently executing.
    // Returns a list of neighbors which should be put in the next frontier.
    // So if the implementing class has access to the next frontier as a field,
    // **do not** call setActive. Helper functions in GDSUtils will do that work.
    virtual std::vector<common::nodeID_t> edgeCompute(common::nodeID_t boundNodeID,
        graph::NbrScanState::Chunk& results, bool fwdEdge) = 0;

    virtual void resetSingleThreadState() {}

    virtual bool terminate(common::NodeOffsetMaskMap&) { return false; }

    virtual std::unique_ptr<EdgeCompute> copy() = 0;
};

class VertexCompute {
public:
    virtual ~VertexCompute() = default;

    // This function is called once on the "main" copy of VertexCompute in the
    // GDSUtils::runVertexCompute function. runVertexCompute loops through
    // each node table T on the graph on which vertexCompute should run and then before
    // parallelizing the computation on T calls this function.
    virtual bool beginOnTable(common::table_id_t) { return true; }

    // This function is called by each worker thread T on each node in the morsel that T grabs.
    // Does any vertex-centric work that is needed while running on the curNodeID. This function
    // should itself do the work of checking if any work should be done on the vertex or not. Note
    // that this contrasts with how EdgeCompute::edgeCompute() should be implemented, where the
    // GDSUtils helper functions call isActive on nodes to check if any work should be done for
    // the edges of a node. Instead, here GDSUtils helper functions for VertexCompute blindly run
    // the function on each node in a graph.
    virtual void vertexCompute(const graph::VertexScanState::Chunk&) {}
    virtual void vertexCompute(common::offset_t, common::offset_t, common::table_id_t) {}
    // This function assumes the number of nodes is small (sparse) and morsel driven parallelism
    // is not necessary. It should not be used in parallel computations.
    virtual void vertexCompute(common::table_id_t) {}

    virtual std::unique_ptr<VertexCompute> copy() = 0;
};

} // namespace function
} // namespace lbug
