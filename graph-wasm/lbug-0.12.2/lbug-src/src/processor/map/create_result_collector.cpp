#include "processor/operator/result_collector.h"
#include "processor/plan_mapper.h"
#include "processor/result/factorized_table_util.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::binder;

namespace lbug {
namespace processor {

std::unique_ptr<ResultCollector> PlanMapper::createResultCollector(AccumulateType accumulateType,
    const expression_vector& expressions, Schema* schema,
    std::unique_ptr<PhysicalOperator> prevOperator) {
    std::vector<DataPos> payloadsPos;
    for (auto& expr : expressions) {
        payloadsPos.push_back(getDataPos(*expr, *schema));
    }
    auto tableSchema = FactorizedTableUtils::createFTableSchema(expressions, *schema);
    if (accumulateType == AccumulateType::OPTIONAL_) {
        auto columnSchema = ColumnSchema(false /* isUnFlat */, INVALID_DATA_CHUNK_POS,
            LogicalTypeUtils::getRowLayoutSize(LogicalType::BOOL()));
        tableSchema.appendColumn(std::move(columnSchema));
    }
    auto table = std::make_shared<FactorizedTable>(storage::MemoryManager::Get(*clientContext),
        tableSchema.copy());
    auto sharedState = std::make_shared<ResultCollectorSharedState>(std::move(table));
    auto opInfo = ResultCollectorInfo(accumulateType, std::move(tableSchema), payloadsPos);
    auto printInfo = std::make_unique<ResultCollectorPrintInfo>(expressions, accumulateType);
    auto op = std::make_unique<ResultCollector>(std::move(opInfo), std::move(sharedState),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
    op->setDescriptor(std::make_unique<ResultSetDescriptor>(schema));
    return op;
}

} // namespace processor
} // namespace lbug
