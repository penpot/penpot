#include "common/system_config.h"
#include "planner/operator/logical_union.h"
#include "processor/operator/table_scan/union_all_scan.h"
#include "processor/plan_mapper.h"

using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapUnionAll(const LogicalOperator* logicalOperator) {
    auto& logicalUnionAll = logicalOperator->constCast<LogicalUnion>();
    auto outSchema = logicalUnionAll.getSchema();
    // append result collectors to each child
    std::vector<std::unique_ptr<PhysicalOperator>> prevOperators;
    std::vector<std::shared_ptr<FactorizedTable>> tables;
    for (auto i = 0u; i < logicalOperator->getNumChildren(); ++i) {
        auto child = logicalOperator->getChild(i);
        auto childSchema = logicalUnionAll.getSchemaBeforeUnion(i);
        auto prevOperator = mapOperator(child.get());
        auto resultCollector = createResultCollector(AccumulateType::REGULAR,
            childSchema->getExpressionsInScope(), childSchema, std::move(prevOperator));
        tables.push_back(resultCollector->getResultFTable());
        prevOperators.push_back(std::move(resultCollector));
    }
    // append union all
    std::vector<DataPos> outputPositions;
    std::vector<uint32_t> columnIndices;
    auto expressionsToUnion = logicalUnionAll.getExpressionsToUnion();
    for (auto i = 0u; i < expressionsToUnion.size(); ++i) {
        auto expression = expressionsToUnion[i];
        outputPositions.emplace_back(outSchema->getExpressionPos(*expression));
        columnIndices.push_back(i);
    }
    auto info = UnionAllScanInfo(std::move(outputPositions), std::move(columnIndices));
    auto maxMorselSize = tables[0]->hasUnflatCol() ? 1 : DEFAULT_VECTOR_CAPACITY;
    auto unionSharedState = make_shared<UnionAllScanSharedState>(std::move(tables), maxMorselSize);
    auto printInfo = std::make_unique<UnionAllScanPrintInfo>(expressionsToUnion);
    auto scan = make_unique<UnionAllScan>(std::move(info), unionSharedState, getOperatorID(),
        std::move(printInfo));
    for (auto& child : prevOperators) {
        scan->addChild(std::move(child));
    }
    return scan;
}

} // namespace processor
} // namespace lbug
