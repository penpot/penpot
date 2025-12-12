#include "planner/operator/logical_create_macro.h"
#include "processor/operator/macro/create_macro.h"
#include "processor/plan_mapper.h"
#include "processor/result/factorized_table_util.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapCreateMacro(
    const LogicalOperator* logicalOperator) {
    auto& logicalCreateMacro = logicalOperator->constCast<LogicalCreateMacro>();
    auto createMacroInfo =
        CreateMacroInfo(logicalCreateMacro.getMacroName(), logicalCreateMacro.getMacro());
    auto printInfo = std::make_unique<CreateMacroPrintInfo>(createMacroInfo.macroName);
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(
        storage::MemoryManager::Get(*clientContext));
    return std::make_unique<CreateMacro>(std::move(createMacroInfo), std::move(messageTable),
        getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
