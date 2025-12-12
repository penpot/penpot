#include "planner/operator/logical_noop.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapNoop(const LogicalOperator* logicalOperator) {
    std::vector<std::unique_ptr<PhysicalOperator>> children;
    for (auto child : logicalOperator->getChildren()) {
        children.push_back(mapOperator(child.get()));
    }
    auto noop = logicalOperator->constPtrCast<LogicalNoop>();
    auto idx = noop->getMessageChildIdx();
    KU_ASSERT(idx < children.size());
    auto child = children[idx].get();
    // LCOV_EXCL_START
    if (!child->isSink()) {
        throw common::InternalException(
            common::stringFormat("Trying to propagate result table from a non sink operator. This "
                                 "should never happen."));
    }
    // LCOV_EXCL_STOP
    auto fTable = child->ptrCast<Sink>()->getResultFTable();
    auto op = std::make_unique<DummySimpleSink>(fTable, getOperatorID());
    for (auto& childOp : children) {
        op->addChild(std::move(childOp));
    }
    return op;
}

} // namespace processor
} // namespace lbug
