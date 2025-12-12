#include "binder/expression/expression_util.h"
#include "common/exception/message.h"
#include "common/exception/runtime.h"
#include "planner/operator/logical_order_by.h"
#include "processor/operator/order_by/order_by.h"
#include "processor/operator/order_by/order_by_merge.h"
#include "processor/operator/order_by/order_by_scan.h"
#include "processor/operator/order_by/top_k.h"
#include "processor/operator/order_by/top_k_scanner.h"
#include "processor/plan_mapper.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapOrderBy(const LogicalOperator* logicalOperator) {
    auto& logicalOrderBy = logicalOperator->constCast<LogicalOrderBy>();
    auto outSchema = logicalOrderBy.getSchema();
    auto inSchema = logicalOrderBy.getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOrderBy.getChild(0).get());
    auto keyExpressions = logicalOrderBy.getExpressionsToOrderBy();
    auto payloadExpressions = inSchema->getExpressionsInScope();
    std::vector<DataPos> payloadsPos;
    std::vector<LogicalType> payloadTypes;
    expression_map<ft_col_idx_t> payloadToColIdx;
    auto payloadSchema = FactorizedTableSchema();
    auto mayContainUnFlatKey = inSchema->getNumGroups() == 1;
    for (auto i = 0u; i < payloadExpressions.size(); ++i) {
        auto expression = payloadExpressions[i];
        auto [dataChunkPos, vectorPos] = inSchema->getExpressionPos(*expression);
        payloadsPos.emplace_back(dataChunkPos, vectorPos);
        payloadTypes.push_back(expression->dataType.copy());
        if (!inSchema->getGroup(dataChunkPos)->isFlat() && !mayContainUnFlatKey) {
            // payload is unFlat and not in the same group as keys
            auto columnSchema =
                ColumnSchema(true /* isUnFlat */, dataChunkPos, sizeof(overflow_value_t));
            payloadSchema.appendColumn(std::move(columnSchema));
        } else {
            auto columnSchema = ColumnSchema(false /* isUnFlat */, dataChunkPos,
                LogicalTypeUtils::getRowLayoutSize(expression->getDataType()));
            payloadSchema.appendColumn(std::move(columnSchema));
        }
        payloadToColIdx.insert({expression, i});
    }
    std::vector<DataPos> keysPos;
    std::vector<LogicalType> keyTypes;
    std::vector<uint32_t> keyInPayloadPos;
    for (auto& expression : keyExpressions) {
        keysPos.emplace_back(inSchema->getExpressionPos(*expression));
        keyTypes.push_back(expression->getDataType().copy());
        KU_ASSERT(payloadToColIdx.contains(expression));
        keyInPayloadPos.push_back(payloadToColIdx.at(expression));
    }
    std::vector<DataPos> outPos;
    for (auto& expression : payloadExpressions) {
        outPos.emplace_back(outSchema->getExpressionPos(*expression));
    }
    auto orderByDataInfo = OrderByDataInfo(keysPos, payloadsPos, LogicalType::copy(keyTypes),
        LogicalType::copy(payloadTypes), logicalOrderBy.getIsAscOrders(), std::move(payloadSchema),
        std::move(keyInPayloadPos));
    if (logicalOrderBy.hasLimitNum()) {
        auto limitExpr = logicalOrderBy.getLimitNum();
        if (!ExpressionUtil::canEvaluateAsLiteral(*limitExpr)) {
            throw RuntimeException{
                ExceptionMessage::invalidSkipLimitParam(limitExpr->toString(), "limit")};
        }
        auto limitNum = ExpressionUtil::evaluateAsSkipLimit(*limitExpr);
        uint64_t skipNum = 0;
        if (logicalOrderBy.hasSkipNum()) {
            auto skipExpr = logicalOrderBy.getSkipNum();
            if (!ExpressionUtil::canEvaluateAsLiteral(*skipExpr)) {
                throw RuntimeException{
                    ExceptionMessage::invalidSkipLimitParam(skipExpr->toString(), "skip")};
            }
            skipNum = ExpressionUtil::evaluateAsSkipLimit(*skipExpr);
        }
        auto topKSharedState = std::make_shared<TopKSharedState>();
        auto printInfo =
            std::make_unique<TopKPrintInfo>(keyExpressions, payloadExpressions, skipNum, limitNum);
        auto topK = make_unique<TopK>(std::move(orderByDataInfo), topKSharedState, skipNum,
            limitNum, std::move(prevOperator), getOperatorID(), printInfo->copy());
        topK->setDescriptor(std::make_unique<ResultSetDescriptor>(inSchema));
        auto scan =
            std::make_unique<TopKScan>(outPos, topKSharedState, getOperatorID(), printInfo->copy());
        scan->addChild(std::move(topK));
        return scan;
    }
    auto orderBySharedState = std::make_shared<SortSharedState>();
    auto printInfo = std::make_unique<OrderByPrintInfo>(keyExpressions, payloadExpressions);
    auto orderBy = make_unique<OrderBy>(std::move(orderByDataInfo), orderBySharedState,
        std::move(prevOperator), getOperatorID(), printInfo->copy());
    orderBy->setDescriptor(std::make_unique<ResultSetDescriptor>(inSchema));
    auto dispatcher = std::make_shared<KeyBlockMergeTaskDispatcher>();
    auto orderByMerge = make_unique<OrderByMerge>(orderBySharedState, std::move(dispatcher),
        getOperatorID(), printInfo->copy());
    orderByMerge->addChild(std::move(orderBy));
    auto scan = std::make_unique<OrderByScan>(outPos, orderBySharedState, getOperatorID(),
        printInfo->copy());
    scan->addChild(std::move(orderByMerge));
    return scan;
}

} // namespace processor
} // namespace lbug
