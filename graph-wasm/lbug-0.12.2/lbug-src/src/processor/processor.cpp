#include "processor/processor.h"

#include "common/task_system/progress_bar.h"
#include "main/query_result.h"
#include "processor/operator/sink.h"
#include "processor/physical_plan.h"
#include "processor/processor_task.h"

using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace processor {

#if defined(__APPLE__)
QueryProcessor::QueryProcessor(uint64_t numThreads, uint32_t threadQos) {
    taskScheduler = std::make_unique<TaskScheduler>(numThreads, threadQos);
}
#else
QueryProcessor::QueryProcessor(uint64_t numThreads) {
    taskScheduler = std::make_unique<TaskScheduler>(numThreads);
}
#endif

std::unique_ptr<main::QueryResult> QueryProcessor::execute(PhysicalPlan* physicalPlan,
    ExecutionContext* context) {
    auto lastOperator = physicalPlan->lastOperator.get();
    // The root pipeline(task) consists of operators and its prevOperator only, because we
    // expect to have linear plans. For binary operators, e.g., HashJoin, we  keep probe and its
    // prevOperator in the same pipeline, and decompose build and its prevOperator into another
    // one.
    auto sink = lastOperator->ptrCast<Sink>();
    auto task = std::make_shared<ProcessorTask>(sink, context);
    for (auto i = (int64_t)sink->getNumChildren() - 1; i >= 0; --i) {
        decomposePlanIntoTask(sink->getChild(i), task.get(), context);
    }
    initTask(task.get());
    auto progressBar = ProgressBar::Get(*context->clientContext);
    progressBar->startProgress(context->queryID);
    taskScheduler->scheduleTaskAndWaitOrError(task, context);
    progressBar->endProgress(context->queryID);
    return sink->getQueryResult();
}

void QueryProcessor::decomposePlanIntoTask(PhysicalOperator* op, Task* task,
    ExecutionContext* context) {
    if (op->isSource()) {
        ProgressBar::Get(*context->clientContext)->addPipeline();
    }
    if (op->isSink()) {
        auto childTask = std::make_unique<ProcessorTask>(ku_dynamic_cast<Sink*>(op), context);
        for (auto i = (int64_t)op->getNumChildren() - 1; i >= 0; --i) {
            decomposePlanIntoTask(op->getChild(i), childTask.get(), context);
        }
        task->addChildTask(std::move(childTask));
    } else {
        // Schedule the right most side (e.g., build side of the hash join) first.
        for (auto i = (int64_t)op->getNumChildren() - 1; i >= 0; --i) {
            decomposePlanIntoTask(op->getChild(i), task, context);
        }
    }
}

void QueryProcessor::initTask(Task* task) {
    auto processorTask = ku_dynamic_cast<ProcessorTask*>(task);
    PhysicalOperator* op = processorTask->sink;
    while (!op->isSource()) {
        if (!op->isParallel()) {
            task->setSingleThreadedTask();
        }
        op = op->getChild(0);
    }
    if (!op->isParallel()) {
        task->setSingleThreadedTask();
    }
    for (auto& child : task->children) {
        initTask(child.get());
    }
}

} // namespace processor
} // namespace lbug
