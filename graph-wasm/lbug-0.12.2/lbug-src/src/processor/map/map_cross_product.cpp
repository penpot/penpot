#include "common/system_config.h"
#include "planner/operator/logical_cross_product.h"
#include "processor/operator/cross_product.h"
#include "processor/plan_mapper.h"

using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapCrossProduct(
    const LogicalOperator* logicalOperator) {
    auto& logicalCrossProduct = logicalOperator->constCast<LogicalCrossProduct>();
    auto outSchema = logicalCrossProduct.getSchema();
    auto buildChild = logicalCrossProduct.getChild(1);
    // map build side
    auto buildSchema = buildChild->getSchema();
    auto buildSidePrevOperator = mapOperator(buildChild.get());
    auto expressions = buildSchema->getExpressionsInScope();
    auto resultCollector = createResultCollector(logicalCrossProduct.getAccumulateType(),
        expressions, buildSchema, std::move(buildSidePrevOperator));
    // map probe side
    auto probeSidePrevOperator = mapOperator(logicalCrossProduct.getChild(0).get());
    std::vector<DataPos> outVecPos;
    std::vector<uint32_t> colIndicesToScan;
    if (logicalCrossProduct.hasMark()) {
        expressions.push_back(logicalCrossProduct.getMark());
    }
    for (auto i = 0u; i < expressions.size(); ++i) {
        auto expression = expressions[i];
        outVecPos.emplace_back(outSchema->getExpressionPos(*expression));
        colIndicesToScan.push_back(i);
    }
    auto info = CrossProductInfo(std::move(outVecPos), std::move(colIndicesToScan));
    auto table = resultCollector->getResultFTable();
    auto maxMorselSize = table->hasUnflatCol() ? 1 : DEFAULT_VECTOR_CAPACITY;
    auto localState = CrossProductLocalState(table, maxMorselSize);
    auto printInfo = std::make_unique<OPPrintInfo>();
    auto crossProduct = std::make_unique<CrossProduct>(std::move(info), std::move(localState),
        std::move(probeSidePrevOperator), getOperatorID(), std::move(printInfo));
    crossProduct->addChild(std::move(resultCollector));
    if (logicalCrossProduct.getSIPInfo().direction == SIPDirection::PROBE_TO_BUILD) {
        mapSIPJoin(crossProduct.get());
    }
    return crossProduct;
}

} // namespace processor
} // namespace lbug
