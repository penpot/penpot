#include "main/client_context.h"
#include "planner/operator/scan/logical_index_look_up.h"
#include "processor/expression_mapper.h"
#include "processor/operator/index_lookup.h"
#include "processor/plan_mapper.h"
#include "storage/storage_manager.h"
#include "storage/table/node_table.h"

using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapIndexLookup(
    const LogicalOperator* logicalOperator) {
    auto& logicalIndexScan = logicalOperator->constCast<LogicalPrimaryKeyLookup>();
    auto outSchema = logicalIndexScan.getSchema();
    auto child = logicalOperator->getChild(0).get();
    auto prevOperator = mapOperator(child);
    auto storageManager = storage::StorageManager::Get(*clientContext);
    auto exprMapper = ExpressionMapper(child->getSchema());
    std::vector<IndexLookupInfo> indexLookupInfos;
    for (auto i = 0u; i < logicalIndexScan.getNumInfos(); ++i) {
        auto& info = logicalIndexScan.getInfo(i);
        auto nodeTable = storageManager->getTable(info.nodeTableID)->ptrCast<storage::NodeTable>();
        auto offsetPos = DataPos(outSchema->getExpressionPos(*info.offset));
        auto keyEvaluator = exprMapper.getEvaluator(info.key);
        indexLookupInfos.emplace_back(nodeTable, std::move(keyEvaluator), offsetPos);
    }
    auto warningDataPos = getDataPos(logicalIndexScan.getInfo(0).warningExprs, *outSchema);
    binder::expression_vector expressions;
    for (auto i = 0u; i < logicalIndexScan.getNumInfos(); ++i) {
        expressions.push_back(logicalIndexScan.getInfo(i).offset);
    }
    auto printInfo = std::make_unique<IndexLookupPrintInfo>(expressions);
    return std::make_unique<IndexLookup>(std::move(indexLookupInfos), std::move(warningDataPos),
        std::move(prevOperator), getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
