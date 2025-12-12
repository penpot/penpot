#include "common/system_config.h"
#include "planner/operator/logical_accumulate.h"
#include "processor/operator/result_collector.h"
#include "processor/plan_mapper.h"

using namespace lbug::planner;
using namespace lbug::common;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapAccumulate(
    const LogicalOperator* logicalOperator) {
    const auto& acc = logicalOperator->constCast<LogicalAccumulate>();
    auto outSchema = acc.getSchema();
    auto inSchema = acc.getChild(0)->getSchema();
    auto prevOperator = mapOperator(acc.getChild(0).get());
    auto expressions = acc.getPayloads();
    auto resultCollector = createResultCollector(acc.getAccumulateType(), expressions, inSchema,
        std::move(prevOperator));
    auto table = resultCollector->getResultFTable();
    auto maxMorselSize = table->hasUnflatCol() ? 1 : DEFAULT_VECTOR_CAPACITY;
    if (acc.hasMark()) {
        expressions.push_back(acc.getMark());
    }
    physical_op_vector_t children;
    children.push_back(std::move(resultCollector));
    return createFTableScanAligned(expressions, outSchema, table, maxMorselSize,
        std::move(children));
}

} // namespace processor
} // namespace lbug
