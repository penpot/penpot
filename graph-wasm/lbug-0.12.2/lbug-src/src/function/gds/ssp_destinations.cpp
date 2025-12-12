#include "binder/expression/node_expression.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
#include "processor/execution_context.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::processor;
using namespace lbug::graph;
using namespace lbug::main;

namespace lbug {
namespace function {

class SSPDestinationsOutputWriter : public RJOutputWriter {
public:
    SSPDestinationsOutputWriter(ClientContext* context, NodeOffsetMaskMap* outputNodeMask,
        nodeID_t sourceNodeID, Frontier* frontier)
        : RJOutputWriter{context, outputNodeMask, sourceNodeID}, frontier{frontier} {
        lengthVector = createVector(LogicalType::UINT16());
    }

    void beginWritingInternal(table_id_t tableID) override { frontier->pinTableID(tableID); }

    void write(FactorizedTable& fTable, table_id_t tableID, LimitCounter* counter) override {
        auto& sparseFrontier = frontier->cast<SparseFrontier>();
        for (auto [offset, _] : sparseFrontier.getCurrentData()) {
            write(fTable, {offset, tableID}, counter);
        }
    }

    void write(FactorizedTable& fTable, nodeID_t dstNodeID, LimitCounter* counter) override {
        if (!inOutputNodeMask(dstNodeID.offset)) { // Skip dst if it not is in scope.
            return;
        }
        if (sourceNodeID_ == dstNodeID) { // Skip writing source node.
            return;
        }
        auto iter = frontier->getIteration(dstNodeID.offset);
        if (iter == FRONTIER_UNVISITED) { // Skip if dst is not visited.
            return;
        }
        dstNodeIDVector->setValue<nodeID_t>(0, dstNodeID);
        lengthVector->setValue<uint16_t>(0, iter);
        fTable.append(vectors);
        if (counter != nullptr) {
            counter->increase(1);
        }
    }

    std::unique_ptr<RJOutputWriter> copy() override {
        return std::make_unique<SSPDestinationsOutputWriter>(context, outputNodeMask, sourceNodeID_,
            frontier);
    }

private:
    Frontier* frontier;
    std::unique_ptr<ValueVector> lengthVector;
};

class SSPDestinationsEdgeCompute : public SPEdgeCompute {
public:
    explicit SSPDestinationsEdgeCompute(SPFrontierPair* frontierPair)
        : SPEdgeCompute{frontierPair} {};

    std::vector<nodeID_t> edgeCompute(nodeID_t, NbrScanState::Chunk& resultChunk, bool) override {
        std::vector<nodeID_t> activeNodes;
        resultChunk.forEach([&](auto neighbors, auto, auto i) {
            auto nbrNode = neighbors[i];
            auto iter = frontierPair->getNextFrontierValue(nbrNode.offset);
            if (iter == FRONTIER_UNVISITED) {
                activeNodes.push_back(nbrNode);
            }
        });
        return activeNodes;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<SSPDestinationsEdgeCompute>(frontierPair);
    }
};

// Single shortest path algorithm. Only destinations are tracked (reachability query).
// If there are multiple path to a destination. Only one of the path is tracked.
class SingleSPDestinationsAlgorithm : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return SingleSPDestinationsFunction::name; }

    expression_vector getResultColumns(const RJBindData& bindData) const override {
        expression_vector columns;
        columns.push_back(bindData.nodeInput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.nodeOutput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.lengthExpr);
        return columns;
    }

    std::unique_ptr<RJAlgorithm> copy() const override {
        return std::make_unique<SingleSPDestinationsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context, const RJBindData&,
        RecursiveExtendSharedState* sharedState) override {
        auto graph = sharedState->graph.get();
        auto denseFrontier = DenseFrontier::getUninitializedFrontier(context, graph);
        auto frontierPair = std::make_unique<SPFrontierPair>(std::move(denseFrontier));
        auto edgeCompute = std::make_unique<SSPDestinationsEdgeCompute>(frontierPair.get());
        auto auxiliaryState = std::make_unique<EmptyGDSAuxiliaryState>();
        return std::make_unique<GDSComputeState>(std::move(frontierPair), std::move(edgeCompute),
            std::move(auxiliaryState));
    }

    std::unique_ptr<RJOutputWriter> getOutputWriter(ExecutionContext* context, const RJBindData&,
        GDSComputeState& computeState, nodeID_t sourceNodeID,
        RecursiveExtendSharedState* sharedState) override {
        auto frontier = computeState.frontierPair->ptrCast<SPFrontierPair>()->getFrontier();
        return std::make_unique<SSPDestinationsOutputWriter>(context->clientContext,
            sharedState->getOutputNodeMaskMap(), sourceNodeID, frontier);
    }
};

std::unique_ptr<RJAlgorithm> SingleSPDestinationsFunction::getAlgorithm() {
    return std::make_unique<SingleSPDestinationsAlgorithm>();
}

} // namespace function
} // namespace lbug
