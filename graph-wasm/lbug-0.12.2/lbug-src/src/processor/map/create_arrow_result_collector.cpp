#include "processor/operator/arrow_result_collector.h"
#include "processor/plan_mapper.h"

using namespace lbug::common;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::createArrowResultCollector(
    ArrowResultConfig arrowConfig, const binder::expression_vector& expressions,
    planner::Schema* schema, std::unique_ptr<PhysicalOperator> prevOperator) {
    std::vector<DataPos> columnDataPos;
    std::vector<LogicalType> columnTypes;
    for (auto& expr : expressions) {
        columnDataPos.push_back(getDataPos(*expr, *schema));
        columnTypes.push_back(expr->getDataType().copy());
    }
    auto sharedState = std::make_shared<ArrowResultCollectorSharedState>();
    auto opInfo =
        ArrowResultCollectorInfo(arrowConfig.chunkSize, columnDataPos, std::move(columnTypes));
    auto printInfo = OPPrintInfo::EmptyInfo();
    auto op = std::make_unique<ArrowResultCollector>(sharedState, std::move(opInfo),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
    op->setDescriptor(std::make_unique<ResultSetDescriptor>(schema));
    return op;
}

} // namespace processor
} // namespace lbug
