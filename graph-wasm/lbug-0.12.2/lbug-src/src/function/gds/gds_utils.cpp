#include "function/gds/gds_utils.h"

#include "binder/expression/property_expression.h"
#include "catalog/catalog_entry/table_catalog_entry.h"
#include "common/exception/interrupt.h"
#include "common/task_system/task_scheduler.h"
#include "function/gds/gds_task.h"
#include "graph/graph.h"
#include "graph/graph_entry.h"
#include "main/client_context.h"
#include "transaction/transaction.h"

using namespace lbug::common;
using namespace lbug::catalog;
using namespace lbug::function;
using namespace lbug::processor;
using namespace lbug::graph;

namespace lbug {
namespace function {

static std::shared_ptr<FrontierTask> getFrontierTask(const main::ClientContext* context,
    const GraphRelInfo& relInfo, Graph* graph, ExtendDirection extendDirection,
    const GDSComputeState& computeState, std::vector<std::string> propertiesToScan) {
    auto info = FrontierTaskInfo(relInfo.srcTableID, relInfo.dstTableID, relInfo.relGroupEntry,
        graph, extendDirection, *computeState.edgeCompute, std::move(propertiesToScan));
    computeState.beginFrontierCompute(info.getBoundTableID(), info.getNbrTableID());
    auto numThreads = context->getMaxNumThreadForExec();
    auto sharedState =
        std::make_shared<FrontierTaskSharedState>(numThreads, *computeState.frontierPair);
    auto maxOffset =
        graph->getMaxOffset(transaction::Transaction::Get(*context), info.getBoundTableID());
    sharedState->morselDispatcher.init(maxOffset);
    return std::make_shared<FrontierTask>(numThreads, info, sharedState);
}

static void scheduleFrontierTask(ExecutionContext* context, const GraphRelInfo& relInfo,
    Graph* graph, ExtendDirection extendDirection, const GDSComputeState& computeState,
    std::vector<std::string> propertiesToScan) {
    auto clientContext = context->clientContext;
    auto task = getFrontierTask(clientContext, relInfo, graph, extendDirection, computeState,
        std::move(propertiesToScan));
    if (computeState.frontierPair->getState() == GDSDensityState::SPARSE) {
        task->runSparse();
        return;
    }

    // GDSUtils::runFrontiersUntilConvergence is called from a GDSCall operator, which is
    // already executed by a worker thread Tm of the task scheduler. So this function is
    // executed by Tm. Because this function will monitor the task and wait for it to
    // complete, running GDS algorithms effectively "loses" Tm. This can even lead to the
    // query processor to halt, e.g., if there is a single worker thread in the system, and
    // more generally decrease the number of worker threads by 1. Therefore, we instruct
    // scheduleTaskAndWaitOrError to start a new thread by passing true as the last
    // argument.
    TaskScheduler::Get(*context->clientContext)
        ->scheduleTaskAndWaitOrError(task, context, true /* launchNewWorkerThread */);
}

static void runOneIteration(ExecutionContext* context, Graph* graph,
    ExtendDirection extendDirection, const GDSComputeState& compState,
    const std::vector<std::string>& propertiesToScan) {
    for (auto info : graph->getGraphEntry()->nodeInfos) {
        for (const auto& relInfo : graph->getRelInfos(info.entry->getTableID())) {
            if (context->clientContext->interrupted()) {
                throw InterruptException{};
            }
            switch (extendDirection) {
            case ExtendDirection::FWD: {
                scheduleFrontierTask(context, relInfo, graph, ExtendDirection::FWD, compState,
                    propertiesToScan);
            } break;
            case ExtendDirection::BWD: {
                scheduleFrontierTask(context, relInfo, graph, ExtendDirection::BWD, compState,
                    propertiesToScan);
            } break;
            case ExtendDirection::BOTH: {
                scheduleFrontierTask(context, relInfo, graph, ExtendDirection::FWD, compState,
                    propertiesToScan);
                scheduleFrontierTask(context, relInfo, graph, ExtendDirection::BWD, compState,
                    propertiesToScan);
            } break;
            default:
                KU_UNREACHABLE;
            }
        }
    }
}

void GDSUtils::runAlgorithmEdgeCompute(ExecutionContext* context, GDSComputeState& compState,
    Graph* graph, ExtendDirection extendDirection, uint64_t maxIteration) {
    auto frontierPair = compState.frontierPair.get();
    while (frontierPair->continueNextIter(maxIteration)) {
        frontierPair->beginNewIteration();
        runOneIteration(context, graph, extendDirection, compState, {});
    }
}

void GDSUtils::runFTSEdgeCompute(ExecutionContext* context, GDSComputeState& compState,
    Graph* graph, ExtendDirection extendDirection,
    const std::vector<std::string>& propertiesToScan) {
    compState.frontierPair->beginNewIteration();
    runOneIteration(context, graph, extendDirection, compState, propertiesToScan);
}

void GDSUtils::runRecursiveJoinEdgeCompute(ExecutionContext* context, GDSComputeState& compState,
    Graph* graph, ExtendDirection extendDirection, uint64_t maxIteration,
    NodeOffsetMaskMap* outputNodeMask, const std::vector<std::string>& propertiesToScan) {
    auto frontierPair = compState.frontierPair.get();
    compState.edgeCompute->resetSingleThreadState();
    while (frontierPair->continueNextIter(maxIteration)) {
        frontierPair->beginNewIteration();
        if (outputNodeMask != nullptr && compState.edgeCompute->terminate(*outputNodeMask)) {
            break;
        }
        runOneIteration(context, graph, extendDirection, compState, propertiesToScan);
        if (frontierPair->needSwitchToDense(
                context->clientContext->getClientConfig()->sparseFrontierThreshold)) {
            compState.switchToDense(context, graph);
        }
    }
}

static void runVertexComputeInternal(const TableCatalogEntry* currentEntry,
    GDSDensityState densityState, const Graph* graph, std::shared_ptr<VertexComputeTask> task,
    ExecutionContext* context) {
    if (densityState == GDSDensityState::SPARSE) {
        task->runSparse();
        return;
    }
    auto maxOffset = graph->getMaxOffset(transaction::Transaction::Get(*context->clientContext),
        currentEntry->getTableID());
    auto sharedState = task->getSharedState();
    sharedState->morselDispatcher.init(maxOffset);
    TaskScheduler::Get(*context->clientContext)
        ->scheduleTaskAndWaitOrError(task, context, true /* launchNewWorkerThread */);
}

void GDSUtils::runVertexCompute(ExecutionContext* context, GDSDensityState densityState,
    Graph* graph, VertexCompute& vc, const std::vector<std::string>& propertiesToScan) {
    auto maxThreads = context->clientContext->getMaxNumThreadForExec();
    auto sharedState = std::make_shared<VertexComputeTaskSharedState>(maxThreads);
    for (const auto& nodeInfo : graph->getGraphEntry()->nodeInfos) {
        auto entry = nodeInfo.entry;
        if (!vc.beginOnTable(entry->getTableID())) {
            continue;
        }
        auto info = VertexComputeTaskInfo(vc, graph, entry, propertiesToScan);
        auto task = std::make_shared<VertexComputeTask>(maxThreads, info, sharedState);
        runVertexComputeInternal(entry, densityState, graph, task, context);
    }
}

void GDSUtils::runVertexCompute(ExecutionContext* context, GDSDensityState densityState,
    Graph* graph, VertexCompute& vc) {
    runVertexCompute(context, densityState, graph, vc, std::vector<std::string>{});
}

void GDSUtils::runVertexCompute(ExecutionContext* context, GDSDensityState densityState,
    Graph* graph, VertexCompute& vc, TableCatalogEntry* entry,
    const std::vector<std::string>& propertiesToScan) {
    auto maxThreads = context->clientContext->getMaxNumThreadForExec();
    auto info = VertexComputeTaskInfo(vc, graph, entry, propertiesToScan);
    auto sharedState = std::make_shared<VertexComputeTaskSharedState>(maxThreads);
    if (!vc.beginOnTable(entry->getTableID())) {
        return;
    }
    auto task = std::make_shared<VertexComputeTask>(maxThreads, info, sharedState);
    runVertexComputeInternal(entry, densityState, graph, task, context);
}

} // namespace function
} // namespace lbug
