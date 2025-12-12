#include "binder/expression/node_expression.h"
#include "function/gds/auxiliary_state/path_auxiliary_state.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
#include "graph/graph.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace function {

class VarLenPathsOutputWriter final : public PathsOutputWriter {
public:
    VarLenPathsOutputWriter(main::ClientContext* context, NodeOffsetMaskMap* outputNodeMask,
        nodeID_t sourceNodeID, PathsOutputWriterInfo info, BaseBFSGraph& bfsGraph)
        : PathsOutputWriter{context, outputNodeMask, sourceNodeID, info, bfsGraph} {}

    void writeInternal(FactorizedTable& fTable, nodeID_t dstNodeID,
        LimitCounter* counter) override {
        auto firstParent = findFirstParent(dstNodeID.offset);
        if (firstParent == nullptr) {
            if (sourceNodeID_ == dstNodeID && info.lowerBound == 0) {
                // We still output a path from src to src if required path length is 0.
                // e.g. MATCH (a)-[e*0..]->
                // "a" needs to be in the output
                writePath({});
                fTable.append(vectors);
                updateCounterAndTerminate(counter);
            }
            return;
        }
        if (firstParent->getIter() < info.lowerBound) { // Skip if lower bound is not met.
            return;
        }
        if (!info.hasNodeMask() && info.semantic == PathSemantic::WALK) {
            dfsFast(firstParent, fTable, counter);
            return;
        }
        dfsSlow(firstParent, fTable, counter);
    }

    std::unique_ptr<RJOutputWriter> copy() override {
        return std::make_unique<VarLenPathsOutputWriter>(context, outputNodeMask, sourceNodeID_,
            info, bfsGraph);
    }
};

class VarLenJoinsEdgeCompute : public EdgeCompute {
public:
    VarLenJoinsEdgeCompute(DenseSparseDynamicFrontierPair* frontierPair,
        BFSGraphManager* bfsGraphManager)
        : frontierPair{frontierPair}, bfsGraphManager{bfsGraphManager} {
        block = bfsGraphManager->getCurrentGraph()->addNewBlock();
    };

    std::vector<nodeID_t> edgeCompute(nodeID_t boundNodeID, graph::NbrScanState::Chunk& chunk,
        bool fwdEdge) override {
        std::vector<nodeID_t> activeNodes;
        chunk.forEach([&](auto neighbors, auto propertyVectors, auto i) {
            // We should always update the nbrID in variable length joins
            auto nbrNodeID = neighbors[i];
            auto edgeID = propertyVectors[0]->template getValue<relID_t>(i);
            if (!block->hasSpace()) {
                block = bfsGraphManager->getCurrentGraph()->addNewBlock();
            }
            bfsGraphManager->getCurrentGraph()->addParent(frontierPair->getCurrentIter(),
                boundNodeID, edgeID, nbrNodeID, fwdEdge, block);
            activeNodes.push_back(nbrNodeID);
        });
        return activeNodes;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<VarLenJoinsEdgeCompute>(frontierPair, bfsGraphManager);
    }

private:
    DenseSparseDynamicFrontierPair* frontierPair;
    BFSGraphManager* bfsGraphManager;
    ObjectBlock<ParentList>* block = nullptr;
};

/**
 * Algorithm for parallel all shortest paths computation, so all shortest paths from a source to
 * is returned for each destination. If paths are not returned, multiplicities indicate the
 * number of paths to each destination.
 */
class VarLenJoinsAlgorithm final : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return VarLenJoinsFunction::name; }

    // return srcNodeID, dstNodeID, length, [direction, pathNodeIDs, pathEdgeIDs] (if track path)
    expression_vector getResultColumns(const RJBindData& bindData) const override {
        expression_vector columns;
        columns.push_back(bindData.nodeInput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.nodeOutput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.lengthExpr);
        if (bindData.writePath) {
            if (bindData.extendDirection == ExtendDirection::BOTH) {
                columns.push_back(bindData.directionExpr);
            }
            columns.push_back(bindData.pathNodeIDsExpr);
            columns.push_back(bindData.pathEdgeIDsExpr);
        }
        return columns;
    }

    std::unique_ptr<RJAlgorithm> copy() const override {
        return std::make_unique<VarLenJoinsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context, const RJBindData&,
        RecursiveExtendSharedState* sharedState) override {
        auto clientContext = context->clientContext;
        auto transaction = transaction::Transaction::Get(*clientContext);
        auto bfsGraph =
            std::make_unique<BFSGraphManager>(sharedState->graph->getMaxOffsetMap(transaction),
                storage::MemoryManager::Get(*clientContext));
        auto currentDenseFrontier =
            DenseFrontier::getUninitializedFrontier(context, sharedState->graph.get());
        auto nextDenseFrontier =
            DenseFrontier::getUninitializedFrontier(context, sharedState->graph.get());
        auto frontierPair = std::make_unique<DenseSparseDynamicFrontierPair>(
            std::move(currentDenseFrontier), std::move(nextDenseFrontier));
        auto edgeCompute =
            std::make_unique<VarLenJoinsEdgeCompute>(frontierPair.get(), bfsGraph.get());
        auto auxiliaryState = std::make_unique<PathAuxiliaryState>(std::move(bfsGraph));
        return std::make_unique<GDSComputeState>(std::move(frontierPair), std::move(edgeCompute),
            std::move(auxiliaryState));
    }

    std::unique_ptr<RJOutputWriter> getOutputWriter(ExecutionContext* context,
        const RJBindData& bindData, GDSComputeState& computeState, common::nodeID_t sourceNodeID,
        processor::RecursiveExtendSharedState* sharedState) override {
        auto bfsGraph = computeState.auxiliaryState->ptrCast<PathAuxiliaryState>()
                            ->getBFSGraphManager()
                            ->getCurrentGraph();
        auto writerInfo = bindData.getPathWriterInfo();
        writerInfo.pathNodeMask = sharedState->getPathNodeMaskMap();
        return std::make_unique<VarLenPathsOutputWriter>(context->clientContext,
            sharedState->getOutputNodeMaskMap(), sourceNodeID, writerInfo, *bfsGraph);
    }
};

std::unique_ptr<RJAlgorithm> VarLenJoinsFunction::getAlgorithm() {
    return std::make_unique<VarLenJoinsAlgorithm>();
}

} // namespace function
} // namespace lbug
