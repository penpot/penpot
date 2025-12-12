#include "planner/operator/logical_projection.h"
#include "processor/expression_mapper.h"
#include "processor/operator/projection.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapProjection(
    const LogicalOperator* logicalOperator) {
    auto& logicalProjection = logicalOperator->constCast<LogicalProjection>();
    auto outSchema = logicalProjection.getSchema();
    auto inSchema = logicalProjection.getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    auto printInfo =
        std::make_unique<ProjectionPrintInfo>(logicalProjection.getExpressionsToProject());
    auto info = ProjectionInfo();
    info.discardedChunkIndices = logicalProjection.getDiscardedGroupsPos();
    auto exprMapper = ExpressionMapper(inSchema);
    for (auto& expr : logicalProjection.getExpressionsToProject()) {
        info.addEvaluator(exprMapper.getEvaluator(expr), getDataPos(*expr, *outSchema));
    }
    return make_unique<Projection>(std::move(info), std::move(prevOperator), getOperatorID(),
        std::move(printInfo));
}

} // namespace processor
} // namespace lbug
