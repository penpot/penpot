#include "planner/operator/persistent/logical_copy_to.h"
#include "processor/operator/persistent/copy_to.h"
#include "processor/plan_mapper.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::planner;
using namespace lbug::storage;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapCopyTo(const LogicalOperator* logicalOperator) {
    auto& logicalCopyTo = logicalOperator->constCast<LogicalCopyTo>();
    auto childSchema = logicalOperator->getChild(0)->getSchema();
    auto prevOperator = mapOperator(logicalOperator->getChild(0).get());
    std::vector<DataPos> vectorsToCopyPos;
    std::vector<bool> isFlat;
    std::vector<LogicalType> types;
    for (auto& expression : childSchema->getExpressionsInScope()) {
        vectorsToCopyPos.emplace_back(childSchema->getExpressionPos(*expression));
        isFlat.push_back(childSchema->getGroup(expression)->isFlat());
        types.push_back(expression->dataType.copy());
    }
    auto exportFunc = logicalCopyTo.getExportFunc();
    auto bindData = logicalCopyTo.getBindData();
    // TODO(Xiyang): Query: COPY (RETURN null) TO '/tmp/1.parquet', the datatype of the first
    // column is ANY, should we solve the type at binder?
    bindData->setDataType(std::move(types));
    auto sharedState = exportFunc.createSharedState();
    auto info =
        CopyToInfo{exportFunc, std::move(bindData), std::move(vectorsToCopyPos), std::move(isFlat)};
    auto printInfo =
        std::make_unique<CopyToPrintInfo>(info.bindData->columnNames, info.bindData->fileName);
    auto copyTo = std::make_unique<CopyTo>(std::move(info), std::move(sharedState),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
    copyTo->setDescriptor(std::make_unique<ResultSetDescriptor>(childSchema));
    return createEmptyFTableScan(FactorizedTable::EmptyTable(MemoryManager::Get(*clientContext)), 0,
        std::move(copyTo));
}

} // namespace processor
} // namespace lbug
