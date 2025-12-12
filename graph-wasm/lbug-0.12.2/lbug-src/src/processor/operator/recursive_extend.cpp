#include "processor/operator/recursive_extend.h"

#include "binder/expression/node_expression.h"
#include "binder/expression/property_expression.h"
#include "common/task_system/progress_bar.h"
#include "function/gds/compute.h"
#include "function/gds/gds_function_collection.h"
#include "function/gds/gds_utils.h"
#include "processor/execution_context.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::binder;
using namespace lbug::function;

namespace lbug {
namespace processor {

// All recursive join computation have the same vertex compute. This vertex compute writes
// result (could be dst, length or path) from a dst node ID to given source node ID.
class RJVertexCompute : public VertexCompute {
public:
    RJVertexCompute(storage::MemoryManager* mm, RecursiveExtendSharedState* sharedState,
        std::unique_ptr<RJOutputWriter> writer, table_id_set_t nbrTableIDSet)
        : mm{mm}, sharedState{sharedState}, writer{std::move(writer)},
          nbrTableIDSet{std::move(nbrTableIDSet)} {
        localFT = sharedState->factorizedTablePool.claimLocalTable(mm);
    }
    ~RJVertexCompute() override { sharedState->factorizedTablePool.returnLocalTable(localFT); }

    bool beginOnTable(table_id_t tableID) override {
        // Nbr node table IDs might be different from graph node table IDs
        // See comment below in RecursiveExtend::executeInternal.
        if (!nbrTableIDSet.contains(tableID)) {
            return false;
        }
        writer->beginWriting(tableID);
        return true;
    }

    void vertexCompute(offset_t startOffset, offset_t endOffset, table_id_t tableID) override {
        for (auto i = startOffset; i < endOffset; ++i) {
            if (sharedState->exceedLimit()) {
                return;
            }
            auto nodeID = nodeID_t{i, tableID};
            writer->write(*localFT, nodeID, sharedState->counter.get());
        }
    }

    void vertexCompute(table_id_t tableID) override {
        writer->write(*localFT, tableID, sharedState->counter.get());
    }

    std::unique_ptr<VertexCompute> copy() override {
        return std::make_unique<RJVertexCompute>(mm, sharedState, writer->copy(), nbrTableIDSet);
    }

private:
    storage::MemoryManager* mm;
    // Shared state storing ftables to materialize output.
    RecursiveExtendSharedState* sharedState;
    FactorizedTable* localFT;
    std::unique_ptr<RJOutputWriter> writer;
    table_id_set_t nbrTableIDSet;
};

static double getRJProgress(offset_t totalNumNodes, offset_t completedNumNodes) {
    if (totalNumNodes == 0) {
        return 0;
    }
    return (double)completedNumNodes / totalNumNodes;
}

static bool requireRelID(const RJAlgorithm& function) {
    if (function.getFunctionName() == WeightedSPPathsFunction::name ||
        function.getFunctionName() == SingleSPPathsFunction::name ||
        function.getFunctionName() == AllSPPathsFunction::name ||
        function.getFunctionName() == AllWeightedSPPathsFunction::name ||
        function.getFunctionName() == VarLenJoinsFunction::name) {
        return true;
    }
    return false;
}

void RecursiveExtend::executeInternal(ExecutionContext* context) {
    auto clientContext = context->clientContext;
    auto transaction = transaction::Transaction::Get(*clientContext);
    auto progressBar = ProgressBar::Get(*clientContext);
    auto graph = sharedState->graph.get();
    auto inputNodeMaskMap = sharedState->getInputNodeMaskMap();
    offset_t totalNumNodes = 0;
    if (inputNodeMaskMap != nullptr) {
        totalNumNodes = inputNodeMaskMap->getNumMaskedNode();
    } else {
        for (auto& tableID : graph->getNodeTableIDs()) {
            totalNumNodes += graph->getMaxOffset(transaction, tableID);
        }
    }
    std::vector<std::string> propertyNames;
    if (requireRelID(*function)) {
        propertyNames.push_back(InternalKeyword::ID);
    }
    if (bindData.weightPropertyExpr != nullptr) {
        propertyNames.push_back(
            bindData.weightPropertyExpr->ptrCast<PropertyExpression>()->getPropertyName());
    }
    offset_t completedNumNodes = 0;
    auto inputNodeTableIDSet = bindData.nodeInput->constCast<NodeExpression>().getTableIDsSet();
    for (auto& tableID : graph->getNodeTableIDs()) {
        // Input node table IDs could be different from graph node table IDs, e.g.
        // Given schema, student-knows->student, teacher-knows->teacher
        // MATCH (a:student)-[e*knows]->(b:student)
        // the graph node table IDs will include both student and teacher.
        if (!inputNodeTableIDSet.contains(tableID)) {
            continue;
        }
        auto calcFunc = [tableID, propertyNames, graph, context, this](offset_t offset) {
            auto clientContext = context->clientContext;
            auto computeState = function->getComputeState(context, bindData, sharedState.get());
            auto sourceNodeID = nodeID_t{offset, tableID};
            computeState->initSource(sourceNodeID);
            GDSUtils::runRecursiveJoinEdgeCompute(context, *computeState, graph,
                bindData.extendDirection, bindData.upperBound, sharedState->getOutputNodeMaskMap(),
                propertyNames);
            auto writer = function->getOutputWriter(context, bindData, *computeState, sourceNodeID,
                sharedState.get());
            auto vertexCompute = std::make_unique<RJVertexCompute>(
                storage::MemoryManager::Get(*clientContext), sharedState.get(), writer->copy(),
                bindData.nodeOutput->constCast<NodeExpression>().getTableIDsSet());
            GDSUtils::runVertexCompute(context, computeState->frontierPair->getState(), graph,
                *vertexCompute);
        };
        auto maxOffset = graph->getMaxOffset(transaction, tableID);
        if (inputNodeMaskMap && inputNodeMaskMap->getOffsetMask(tableID)->isEnabled()) {
            for (const auto& offset :
                inputNodeMaskMap->getOffsetMask(tableID)->range(0, maxOffset)) {
                calcFunc(offset);
                progressBar->updateProgress(context->queryID,
                    getRJProgress(totalNumNodes, completedNumNodes++));
                if (sharedState->exceedLimit()) {
                    break;
                }
            }
        } else {
            for (auto offset = 0u; offset < maxOffset; ++offset) {
                calcFunc(offset);
                progressBar->updateProgress(context->queryID,
                    getRJProgress(totalNumNodes, completedNumNodes++));
                if (sharedState->exceedLimit()) {
                    break;
                }
            }
        }
    }
    sharedState->factorizedTablePool.mergeLocalTables();
}

} // namespace processor
} // namespace lbug
