#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

static PhysicalOperator* getTableScan(const PhysicalOperator* joinRoot) {
    auto op = joinRoot->getChild(0);
    while (op->getOperatorType() != PhysicalOperatorType::TABLE_FUNCTION_CALL) {
        KU_ASSERT(op->getNumChildren() != 0);
        op = op->getChild(0);
    }
    return op;
}

void PlanMapper::mapSIPJoin(PhysicalOperator* joinRoot) {
    auto tableScan = getTableScan(joinRoot);
    auto resultCollector = tableScan->moveUnaryChild();
    joinRoot->addChild(std::move(resultCollector));
}

} // namespace processor
} // namespace lbug
