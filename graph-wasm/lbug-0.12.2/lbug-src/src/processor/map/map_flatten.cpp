#include "planner/operator/logical_flatten.h"
#include "processor/operator/flatten.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapFlatten(const LogicalOperator* logicalOperator) {
    auto& flatten = logicalOperator->constCast<LogicalFlatten>();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    // todo (Xiyang): add print info for flatten
    auto printInfo = std::make_unique<OPPrintInfo>();
    return make_unique<Flatten>(flatten.getGroupPos(), std::move(prevOperator), getOperatorID(),
        std::move(printInfo));
}

} // namespace processor
} // namespace lbug
