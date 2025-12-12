#include "planner/operator/logical_unwind.h"
#include "processor/expression_mapper.h"
#include "processor/operator/physical_operator.h"
#include "processor/operator/unwind.h"
#include "processor/plan_mapper.h"

using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapUnwind(const LogicalOperator* logicalOperator) {
    auto& unwind = logicalOperator->constCast<LogicalUnwind>();
    auto outSchema = unwind.getSchema();
    auto inSchema = unwind.getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    auto dataPos = DataPos(outSchema->getExpressionPos(*unwind.getOutExpr()));
    auto exprMapper = ExpressionMapper(inSchema);
    auto evaluator = exprMapper.getEvaluator(unwind.getInExpr());
    DataPos idPos;
    if (unwind.hasIDExpr()) {
        idPos = getDataPos(*unwind.getIDExpr(), *outSchema);
    }
    auto printInfo = std::make_unique<UnwindPrintInfo>(unwind.getInExpr(), unwind.getOutExpr());
    return std::make_unique<Unwind>(dataPos, idPos, std::move(evaluator), std::move(prevOperator),
        getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
