#include "binder/expression/literal_expression.h"
#include "main/db_config.h"
#include "planner/operator/logical_standalone_call.h"
#include "processor/operator/standalone_call.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapStandaloneCall(
    const LogicalOperator* logicalOperator) {
    auto logicalStandaloneCall = logicalOperator->constPtrCast<LogicalStandaloneCall>();
    auto optionValue =
        logicalStandaloneCall->getOptionValue()->constPtrCast<binder::LiteralExpression>();
    auto standaloneCallInfo =
        StandaloneCallInfo(logicalStandaloneCall->getOption(), optionValue->getValue());
    auto printInfo =
        std::make_unique<StandaloneCallPrintInfo>(logicalStandaloneCall->getOption()->name);
    return std::make_unique<StandaloneCall>(std::move(standaloneCallInfo), getOperatorID(),
        std::move(printInfo));
}

} // namespace processor
} // namespace lbug
