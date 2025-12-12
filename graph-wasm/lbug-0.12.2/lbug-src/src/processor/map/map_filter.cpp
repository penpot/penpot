#include "planner/operator/logical_filter.h"
#include "processor/expression_mapper.h"
#include "processor/operator/filter.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapFilter(const LogicalOperator* logicalOperator) {
    auto& logicalFilter = logicalOperator->constCast<LogicalFilter>();
    auto inSchema = logicalFilter.getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    auto exprMapper = ExpressionMapper(inSchema);
    auto physicalRootExpr = exprMapper.getEvaluator(logicalFilter.getPredicate());
    auto printInfo = std::make_unique<FilterPrintInfo>(logicalFilter.getPredicate());
    return make_unique<Filter>(std::move(physicalRootExpr), logicalFilter.getGroupPosToSelect(),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
