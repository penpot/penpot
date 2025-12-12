#include "binder/expression/node_expression.h"
#include "function/gds/auxiliary_state/path_auxiliary_state.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
// #include "main/client_context.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace function {

class SSPPathsEdgeCompute : public SPEdgeCompute {
public:
    SSPPathsEdgeCompute(SPFrontierPair* frontierPair, BFSGraphManager* bfsGraphManager)
        : SPEdgeCompute{frontierPair}, bfsGraphManager{bfsGraphManager} {
        block = bfsGraphManager->getCurrentGraph()->addNewBlock();
    }

    std::vector<nodeID_t> edgeCompute(nodeID_t boundNodeID, graph::NbrScanState::Chunk& resultChunk,
        bool isFwd) override {
        std::vector<nodeID_t> activeNodes;
        resultChunk.forEach([&](auto neighbors, auto propertyVectors, auto i) {
            auto nbrNodeID = neighbors[i];
            auto iter = frontierPair->getNextFrontierValue(nbrNodeID.offset);
            if (iter == FRONTIER_UNVISITED) {
                if (!block->hasSpace()) {
                    block = bfsGraphManager->getCurrentGraph()->addNewBlock();
                }
                auto edgeID = propertyVectors[0]->template getValue<nodeID_t>(i);
                bfsGraphManager->getCurrentGraph()->addSingleParent(frontierPair->getCurrentIter(),
                    boundNodeID, edgeID, nbrNodeID, isFwd, block);
                activeNodes.push_back(nbrNodeID);
            }
        });
        return activeNodes;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<SSPPathsEdgeCompute>(frontierPair, bfsGraphManager);
    }

private:
    BFSGraphManager* bfsGraphManager;
    ObjectBlock<ParentList>* block = nullptr;
};

// Single shortest path algorithm. Paths are tracked.
// If there are multiple path to a destination. Only one of the path is tracked.
class SingleSPPathsAlgorithm : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return SingleSPPathsFunction::name; }

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
        return std::make_unique<SingleSPPathsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context, const RJBindData&,
        RecursiveExtendSharedState* sharedState) override {
        auto clientContext = context->clientContext;
        auto frontier = DenseFrontier::getUninitializedFrontier(context, sharedState->graph.get());
        auto frontierPair = std::make_unique<SPFrontierPair>(std::move(frontier));
        auto transaction = transaction::Transaction::Get(*context->clientContext);
        auto bfsGraph =
            std::make_unique<BFSGraphManager>(sharedState->graph->getMaxOffsetMap(transaction),
                storage::MemoryManager::Get(*clientContext));
        auto edgeCompute =
            std::make_unique<SSPPathsEdgeCompute>(frontierPair.get(), bfsGraph.get());
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

std::unique_ptr<RJAlgorithm> SingleSPPathsFunction::getAlgorithm() {
    return std::make_unique<SingleSPPathsAlgorithm>();
}

} // namespace function
} // namespace lbug
