#include "binder/expression/node_expression.h"
#include "function/gds/auxiliary_state/path_auxiliary_state.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace function {

class ASPPathsEdgeCompute : public SPEdgeCompute {
public:
    ASPPathsEdgeCompute(SPFrontierPair* frontiersPair, BFSGraphManager* bfsGraphManager)
        : SPEdgeCompute{frontiersPair}, bfsGraphManager{bfsGraphManager} {
        block = bfsGraphManager->getCurrentGraph()->addNewBlock();
    }

    std::vector<nodeID_t> edgeCompute(nodeID_t boundNodeID, graph::NbrScanState::Chunk& resultChunk,
        bool fwdEdge) override {
        std::vector<nodeID_t> activeNodes;
        resultChunk.forEach([&](auto neighbors, auto propertyVectors, auto i) {
            auto nbrNodeID = neighbors[i];
            auto iter = frontierPair->getNextFrontierValue(nbrNodeID.offset);
            // We should update in 2 cases: 1) if nbrID is being visited
            // for the first time, i.e., when its value in the pathLengths frontier is
            // PathLengths::UNVISITED. Or 2) if nbrID has already been visited but in this
            // iteration, so it's value is curIter + 1.
            auto shouldUpdate =
                iter == FRONTIER_UNVISITED || iter == frontierPair->getCurrentIter();
            if (shouldUpdate) {
                if (!block->hasSpace()) {
                    block = bfsGraphManager->getCurrentGraph()->addNewBlock();
                }
                auto edgeID = propertyVectors[0]->template getValue<nodeID_t>(i);
                bfsGraphManager->getCurrentGraph()->addParent(frontierPair->getCurrentIter(),
                    boundNodeID, edgeID, nbrNodeID, fwdEdge, block);
            }
            if (iter == FRONTIER_UNVISITED) {
                activeNodes.push_back(nbrNodeID);
            }
        });
        return activeNodes;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<ASPPathsEdgeCompute>(frontierPair, bfsGraphManager);
    }

private:
    BFSGraphManager* bfsGraphManager;
    ObjectBlock<ParentList>* block = nullptr;
};

// All shortest path algorithm. Paths are tracked.
class AllSPPathsAlgorithm final : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return AllSPPathsFunction::name; }

    expression_vector getResultColumns(const RJBindData& bindData) const override {
        expression_vector columns;
        columns.push_back(bindData.nodeInput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.nodeOutput->constCast<NodeExpression>().getInternalID());
        columns.push_back(bindData.lengthExpr);
        if (bindData.extendDirection == ExtendDirection::BOTH) {
            columns.push_back(bindData.directionExpr);
        }
        columns.push_back(bindData.pathNodeIDsExpr);
        columns.push_back(bindData.pathEdgeIDsExpr);
        return columns;
    }

    std::unique_ptr<RJAlgorithm> copy() const override {
        return std::make_unique<AllSPPathsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context, const RJBindData&,
        RecursiveExtendSharedState* sharedState) override {
        auto clientContext = context->clientContext;
        auto mm = storage::MemoryManager::Get(*clientContext);
        auto denseFrontier =
            DenseFrontier::getUninitializedFrontier(context, sharedState->graph.get());
        auto frontierPair = std::make_unique<SPFrontierPair>(std::move(denseFrontier));
        auto bfsGraph = std::make_unique<BFSGraphManager>(
            sharedState->graph->getMaxOffsetMap(transaction::Transaction::Get(*clientContext)), mm);
        auto edgeCompute =
            std::make_unique<ASPPathsEdgeCompute>(frontierPair.get(), bfsGraph.get());
        auto auxiliaryState = std::make_unique<PathAuxiliaryState>(std::move(bfsGraph));
        return std::make_unique<GDSComputeState>(std::move(frontierPair), std::move(edgeCompute),
            std::move(auxiliaryState));
    }

    std::unique_ptr<RJOutputWriter> getOutputWriter(ExecutionContext* context,
        const RJBindData& bindData, GDSComputeState& computeState, nodeID_t sourceNodeID,
        RecursiveExtendSharedState* sharedState) override {
        auto bfsGraph = computeState.auxiliaryState->ptrCast<PathAuxiliaryState>()
                            ->getBFSGraphManager()
                            ->getCurrentGraph();
        auto writerInfo = bindData.getPathWriterInfo();
        writerInfo.pathNodeMask = sharedState->getPathNodeMaskMap();
        return std::make_unique<SPPathsOutputWriter>(context->clientContext,
            sharedState->getOutputNodeMaskMap(), sourceNodeID, writerInfo, *bfsGraph);
    }
};

std::unique_ptr<RJAlgorithm> AllSPPathsFunction::getAlgorithm() {
    return std::make_unique<AllSPPathsAlgorithm>();
}

} // namespace function
} // namespace lbug
