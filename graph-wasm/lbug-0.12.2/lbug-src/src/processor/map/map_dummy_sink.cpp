#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapDummySink(const LogicalOperator* logicalOperator) {
    auto child = mapOperator(logicalOperator->getChild(0).get());
    auto descriptor = std::make_unique<ResultSetDescriptor>(logicalOperator->getSchema());
    auto sink = std::make_unique<DummySink>(std::move(child), getOperatorID());
    sink->setDescriptor(std::move(descriptor));
    return sink;
}

} // namespace processor
} // namespace lbug
