#include "processor/operator/multiplicity_reducer.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapMultiplicityReducer(
    const LogicalOperator* logicalOperator) {
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    auto printInfo = std::make_unique<OPPrintInfo>();
    return std::make_unique<MultiplicityReducer>(std::move(prevOperator), getOperatorID(),
        std::move(printInfo));
}

} // namespace processor
} // namespace lbug
