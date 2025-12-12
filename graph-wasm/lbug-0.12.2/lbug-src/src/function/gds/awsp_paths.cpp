#include "binder/expression/node_expression.h"
#include "common/exception/interrupt.h"
#include "function/gds/auxiliary_state/path_auxiliary_state.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/rec_joins.h"
#include "function/gds/weight_utils.h"
#include "main/client_context.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::processor;

namespace lbug {
namespace function {

template<typename T>
class AWSPPathsEdgeCompute : public EdgeCompute {
public:
    explicit AWSPPathsEdgeCompute(BFSGraphManager* bfsGraphManager)
        : bfsGraphManager{bfsGraphManager} {
        block = bfsGraphManager->getCurrentGraph()->addNewBlock();
    }

    std::vector<nodeID_t> edgeCompute(nodeID_t boundNodeID, graph::NbrScanState::Chunk& chunk,
        bool fwdEdge) override {
        std::vector<nodeID_t> result;
        chunk.forEach([&](auto neighbors, auto propertyVectors, auto i) {
            auto nbrNodeID = neighbors[i];
            auto edgeID = propertyVectors[0]->template getValue<relID_t>(i);
            auto weight = propertyVectors[1]->template getValue<T>(i);
            WeightUtils::checkWeight(AllWeightedSPPathsFunction::name, weight);
            if (!block->hasSpace()) {
                block = bfsGraphManager->getCurrentGraph()->addNewBlock();
            }
            if (bfsGraphManager->getCurrentGraph()->tryAddParentWithWeight(boundNodeID, edgeID,
                    nbrNodeID, fwdEdge, static_cast<double>(weight), block)) {
                result.push_back(nbrNodeID);
            }
        });
        return result;
    }

    std::unique_ptr<EdgeCompute> copy() override {
        return std::make_unique<AWSPPathsEdgeCompute<T>>(bfsGraphManager);
    }

private:
    BFSGraphManager* bfsGraphManager;
    ObjectBlock<ParentList>* block = nullptr;
};

class AWSPPathsOutputWriter : public PathsOutputWriter {
public:
    AWSPPathsOutputWriter(main::ClientContext* context, NodeOffsetMaskMap* outputNodeMask,
        nodeID_t sourceNodeID, PathsOutputWriterInfo info, BaseBFSGraph& bfsGraph)
        : PathsOutputWriter{context, outputNodeMask, sourceNodeID, info, bfsGraph} {
        costVector = createVector(LogicalType::DOUBLE());
    }

    void writeInternal(FactorizedTable& fTable, nodeID_t dstNodeID,
        LimitCounter* counter) override {
        if (dstNodeID == sourceNodeID_) { // Skip writing
            return;
        }
        auto firstParent = bfsGraph.getParentListHead(dstNodeID.offset);
        if (firstParent == nullptr) { // Skip if dst is not visited.
            return;
        }
        if (firstParent->getCost() == std::numeric_limits<double>::max()) {
            // Skip if dst is not visited.
            return;
        }
        costVector->setValue<double>(0, firstParent->getCost());
        std::vector<ParentList*> curPath;
        curPath.push_back(firstParent);
        auto backtracking = false;
        while (!curPath.empty()) {
            if (context->interrupted()) {
                throw InterruptException{};
            }
            if (curPath[curPath.size() - 1]->getCost() == 0) { // Find source. Start writing path.
                curPath.pop_back();
                writePath(curPath);
                fTable.append(vectors);
                if (updateCounterAndTerminate(counter)) {
                    return;
                }
                backtracking = true;
            }
            auto topIdx = curPath.size() - 1;
            if (backtracking) {
                auto next = curPath[topIdx]->getNextPtr();
                if (next != nullptr) { // Find next top node with the same cost.
                    KU_ASSERT(curPath[topIdx]->getCost() == next->getCost());
                    curPath[topIdx] = next;
                    backtracking = false;
                } else { // Move to next top.
                    curPath.pop_back();
                }
            } else { // Forward track fill path.
                auto parent = bfsGraph.getParentListHead(curPath[topIdx]->getNodeID());
                KU_ASSERT(parent != nullptr);
                curPath.push_back(parent);
                backtracking = false;
            }
        }
    }

    std::unique_ptr<RJOutputWriter> copy() override {
        return std::make_unique<AWSPPathsOutputWriter>(context, outputNodeMask, sourceNodeID_, info,
            bfsGraph);
    }

private:
    std::unique_ptr<ValueVector> costVector;
};

// All weighted shortest path algorithm. Paths are returned.
class AllWeightedSPPathsAlgorithm : public RJAlgorithm {
public:
    std::string getFunctionName() const override { return AllWeightedSPPathsFunction::name; }

    // return srcNodeID, dstNodeID, length, [direction], pathNodeIDs, pathEdgeIDs, weight
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
        columns.push_back(bindData.weightOutputExpr);
        return columns;
    }

    std::unique_ptr<RJAlgorithm> copy() const override {
        return std::make_unique<AllWeightedSPPathsAlgorithm>(*this);
    }

private:
    std::unique_ptr<GDSComputeState> getComputeState(ExecutionContext* context,
        const RJBindData& bindData, RecursiveExtendSharedState* sharedState) override {
        auto clientContext = context->clientContext;
        auto mm = storage::MemoryManager::Get(*clientContext);
        auto graph = sharedState->graph.get();
        auto curDenseFrontier = DenseFrontier::getUninitializedFrontier(context, graph);
        auto nextDenseFrontier = DenseFrontier::getUninitializedFrontier(context, graph);
        auto frontierPair = std::make_unique<DenseSparseDynamicFrontierPair>(
            std::move(curDenseFrontier), std::move(nextDenseFrontier));
        auto bfsGraph = std::make_unique<BFSGraphManager>(
            sharedState->graph->getMaxOffsetMap(transaction::Transaction::Get(*clientContext)), mm);
        std::unique_ptr<GDSComputeState> gdsState;
        WeightUtils::visit(AllWeightedSPPathsFunction::name,
            bindData.weightPropertyExpr->getDataType(), [&]<typename T>(T) {
                auto edgeCompute = std::make_unique<AWSPPathsEdgeCompute<T>>(bfsGraph.get());
                auto auxiliaryState = std::make_unique<WSPPathsAuxiliaryState>(std::move(bfsGraph));
                gdsState = std::make_unique<GDSComputeState>(std::move(frontierPair),
                    std::move(edgeCompute), std::move(auxiliaryState));
            });
        return gdsState;
    }

    std::unique_ptr<RJOutputWriter> getOutputWriter(ExecutionContext* context,
        const RJBindData& bindData, GDSComputeState& computeState, nodeID_t sourceNodeID,
        RecursiveExtendSharedState* sharedState) override {
        auto bfsGraph = computeState.auxiliaryState->ptrCast<WSPPathsAuxiliaryState>()
                            ->getBFSGraphManager()
                            ->getCurrentGraph();
        auto writerInfo = bindData.getPathWriterInfo();
        return std::make_unique<AWSPPathsOutputWriter>(context->clientContext,
            sharedState->getOutputNodeMaskMap(), sourceNodeID, writerInfo, *bfsGraph);
    }
};

std::unique_ptr<RJAlgorithm> AllWeightedSPPathsFunction::getAlgorithm() {
    return std::make_unique<AllWeightedSPPathsAlgorithm>();
}

} // namespace function
} // namespace lbug
