#include "binder/expression/expression_util.h"
#include "common/exception/message.h"
#include "common/exception/runtime.h"
#include "planner/operator/logical_limit.h"
#include "processor/operator/limit.h"
#include "processor/operator/skip.h"
#include "processor/plan_mapper.h"

using namespace lbug::binder;
using namespace lbug::planner;
using namespace lbug::common;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapLimit(const LogicalOperator* logicalOperator) {
    auto& logicalLimit = logicalOperator->constCast<LogicalLimit>();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    auto dataChunkToSelectPos = logicalLimit.getGroupPosToSelect();
    auto groupsPotToLimit = logicalLimit.getGroupsPosToLimit();
    std::unique_ptr<PhysicalOperator> lastOperator = std::move(prevOperator);
    if (logicalLimit.hasSkipNum()) {
        auto skipExpr = logicalLimit.getSkipNum();
        if (!ExpressionUtil::canEvaluateAsLiteral(*skipExpr)) {
            throw RuntimeException{
                ExceptionMessage::invalidSkipLimitParam(skipExpr->toString(), "skip")};
        }
        auto skipNum = ExpressionUtil::evaluateAsSkipLimit(*skipExpr);
        auto printInfo = std::make_unique<SkipPrintInfo>(skipNum);
        lastOperator = make_unique<Skip>(skipNum, std::make_shared<std::atomic_uint64_t>(0),
            dataChunkToSelectPos, groupsPotToLimit, std::move(lastOperator), getOperatorID(),
            printInfo->copy());
    }
    if (logicalLimit.hasLimitNum()) {
        auto limitExpr = logicalLimit.getLimitNum();
        if (!ExpressionUtil::canEvaluateAsLiteral(*limitExpr)) {
            throw RuntimeException{
                ExceptionMessage::invalidSkipLimitParam(limitExpr->toString(), "limit")};
        }
        auto limitNum = ExpressionUtil::evaluateAsSkipLimit(*limitExpr);
        auto printInfo = std::make_unique<LimitPrintInfo>(limitNum);
        lastOperator = make_unique<Limit>(limitNum, std::make_shared<std::atomic_uint64_t>(0),
            dataChunkToSelectPos, groupsPotToLimit, std::move(lastOperator), getOperatorID(),
            printInfo->copy());
    }
    return lastOperator;
}

} // namespace processor
} // namespace lbug
