#include "common/profiler.h"
#include "main/plan_printer.h"
#include "planner/operator/logical_explain.h"
#include "planner/operator/logical_plan.h"
#include "processor/operator/profile.h"
#include "processor/plan_mapper.h"
#include "processor/result/factorized_table_util.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::binder;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapExplain(const LogicalOperator* logicalOperator) {
    auto& logicalExplain = logicalOperator->constCast<LogicalExplain>();
    auto root = mapOperator(logicalExplain.getChild(0).get());
    if (!root->isSink()) {
        auto inSchema = logicalExplain.getChild(0)->getSchema();
        root = createResultCollector(AccumulateType::REGULAR,
            logicalExplain.getInnerResultColumns(), inSchema, std::move(root));
    }
    auto memoryManager = storage::MemoryManager::Get(*clientContext);
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(memoryManager);
    if (logicalExplain.getExplainType() == ExplainType::PROFILE) {
        auto profile = std::make_unique<Profile>(ProfileInfo{}, std::move(messageTable),
            getOperatorID(), OPPrintInfo::EmptyInfo());
        profile->addChild(std::move(root));
        return profile;
    }
    if (logicalExplain.getExplainType() == ExplainType::PHYSICAL_PLAN) {
        auto plan = std::make_unique<PhysicalPlan>(std::move(root));
        auto profiler = std::make_unique<Profiler>();
        auto explainStr = main::PlanPrinter::printPlanToOstream(plan.get(), profiler.get()).str();
        FactorizedTableUtils::appendStringToTable(messageTable.get(), explainStr, memoryManager);
        return std::make_unique<DummySimpleSink>(std::move(messageTable), getOperatorID());
    }
    auto plan = LogicalPlan();
    plan.setLastOperator(logicalExplain.getChild(0));
    auto explainStr = main::PlanPrinter::printPlanToOstream(&plan).str();
    FactorizedTableUtils::appendStringToTable(messageTable.get(), explainStr, memoryManager);
    return std::make_unique<DummySimpleSink>(std::move(messageTable), getOperatorID());
}

} // namespace processor
} // namespace lbug
