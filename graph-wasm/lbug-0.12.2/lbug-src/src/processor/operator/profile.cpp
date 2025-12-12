#include "processor/operator/profile.h"

#include "main/plan_printer.h"
#include "processor/execution_context.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

void Profile::executeInternal(ExecutionContext* context) {
    const auto planInString =
        main::PlanPrinter::printPlanToOstream(info.physicalPlan, context->profiler).str();
    appendMessage(planInString, storage::MemoryManager::Get(*context->clientContext));
}

} // namespace processor
} // namespace lbug
