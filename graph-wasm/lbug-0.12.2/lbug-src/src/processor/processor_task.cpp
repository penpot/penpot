#include "processor/processor_task.h"

#include "common/task_system/progress_bar.h"
#include "main/client_context.h"
#include "main/settings.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

ProcessorTask::ProcessorTask(Sink* sink, ExecutionContext* executionContext)
    : Task{executionContext->clientContext->getCurrentSetting(main::ThreadsSetting::name)
               .getValue<uint64_t>()},
      sharedStateInitialized{false}, sink{sink}, executionContext{executionContext} {}

void ProcessorTask::run() {
    // We need the lock when cloning because multiple threads can be accessing to clone,
    // which is not thread safe
    lock_t lck{taskMtx};
    if (!sharedStateInitialized) {
        sink->initGlobalState(executionContext);
        sharedStateInitialized = true;
    }
    auto taskRoot = sink->copy();
    lck.unlock();
    auto resultSet =
        sink->getResultSet(storage::MemoryManager::Get(*executionContext->clientContext));
    taskRoot->ptrCast<Sink>()->execute(resultSet.get(), executionContext);
}

void ProcessorTask::finalize() {
    ProgressBar::Get(*executionContext->clientContext)->finishPipeline(executionContext->queryID);
    sink->finalize(executionContext);
}

bool ProcessorTask::terminate() {
    return sink->terminate();
}

} // namespace processor
} // namespace lbug
