#include "planner/operator/scan/logical_dummy_scan.h"
#include "processor/expression_mapper.h"
#include "processor/plan_mapper.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapDummyScan(const LogicalOperator*) {
    auto inSchema = std::make_unique<Schema>();
    auto expression = LogicalDummyScan::getDummyExpression();
    auto tableSchema = FactorizedTableSchema();
    // TODO(Ziyi): remove vectors when we have done the refactor of dataChunk.
    std::vector<std::shared_ptr<ValueVector>> vectors;
    std::vector<ValueVector*> vectorsToAppend;
    auto columnSchema = ColumnSchema(false, 0 /* groupID */,
        LogicalTypeUtils::getRowLayoutSize(expression->dataType));
    tableSchema.appendColumn(std::move(columnSchema));
    auto exprMapper = ExpressionMapper(inSchema.get());
    auto expressionEvaluator = exprMapper.getEvaluator(expression);
    auto memoryManager = storage::MemoryManager::Get(*clientContext);
    // expression can be evaluated statically and does not require an actual resultset to init
    expressionEvaluator->init(ResultSet(0) /* dummy resultset */, clientContext);
    expressionEvaluator->evaluate();
    vectors.push_back(expressionEvaluator->resultVector);
    vectorsToAppend.push_back(expressionEvaluator->resultVector.get());
    auto table = std::make_shared<FactorizedTable>(memoryManager, std::move(tableSchema));
    table->append(vectorsToAppend);
    return createEmptyFTableScan(table, 1 /* maxMorselSize */);
}

} // namespace processor
} // namespace lbug
