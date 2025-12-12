#include "planner/operator/ddl/logical_alter.h"
#include "planner/operator/ddl/logical_create_sequence.h"
#include "planner/operator/ddl/logical_create_table.h"
#include "planner/operator/ddl/logical_create_type.h"
#include "planner/operator/ddl/logical_drop.h"
#include "processor/expression_mapper.h"
#include "processor/operator/ddl/alter.h"
#include "processor/operator/ddl/create_sequence.h"
#include "processor/operator/ddl/create_table.h"
#include "processor/operator/ddl/create_type.h"
#include "processor/operator/ddl/drop.h"
#include "processor/plan_mapper.h"
#include "processor/result/factorized_table_util.h"
#include "storage/buffer_manager/memory_manager.h"

using namespace lbug::binder;
using namespace lbug::common;
using namespace lbug::planner;

namespace lbug {
namespace processor {

std::unique_ptr<PhysicalOperator> PlanMapper::mapCreateTable(
    const LogicalOperator* logicalOperator) {
    auto& createTable = logicalOperator->constCast<LogicalCreateTable>();
    auto printInfo = std::make_unique<LogicalCreateTablePrintInfo>(createTable.getInfo()->copy());
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(
        storage::MemoryManager::Get(*clientContext));
    auto sharedState = std::make_shared<CreateTableSharedState>();
    return std::make_unique<CreateTable>(createTable.getInfo()->copy(), messageTable, sharedState,
        getOperatorID(), std::move(printInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapCreateType(
    const LogicalOperator* logicalOperator) {
    auto& createType = logicalOperator->constCast<LogicalCreateType>();
    auto typeName = createType.getExpressionsForPrinting();
    auto printInfo =
        std::make_unique<CreateTypePrintInfo>(typeName, createType.getType().toString());
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(
        storage::MemoryManager::Get(*clientContext));
    return std::make_unique<CreateType>(typeName, createType.getType().copy(),
        std::move(messageTable), getOperatorID(), std::move(printInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapCreateSequence(
    const LogicalOperator* logicalOperator) {
    auto& createSequence = logicalOperator->constCast<LogicalCreateSequence>();
    auto printInfo =
        std::make_unique<CreateSequencePrintInfo>(createSequence.getInfo().sequenceName);
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(
        storage::MemoryManager::Get(*clientContext));
    return std::make_unique<CreateSequence>(createSequence.getInfo(), std::move(messageTable),
        getOperatorID(), std::move(printInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapDrop(const LogicalOperator* logicalOperator) {
    auto& drop = logicalOperator->constCast<LogicalDrop>();
    auto& dropInfo = drop.getDropInfo();
    auto printInfo = std::make_unique<DropPrintInfo>(drop.getDropInfo().name);
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(
        storage::MemoryManager::Get(*clientContext));
    return std::make_unique<Drop>(dropInfo, std::move(messageTable), getOperatorID(),
        std::move(printInfo));
}

std::unique_ptr<PhysicalOperator> PlanMapper::mapAlter(const LogicalOperator* logicalOperator) {
    auto& alter = logicalOperator->constCast<LogicalAlter>();
    std::unique_ptr<evaluator::ExpressionEvaluator> defaultValueEvaluator;
    auto exprMapper = ExpressionMapper(alter.getSchema());
    if (alter.getInfo()->alterType == AlterType::ADD_PROPERTY) {
        auto& addPropInfo = alter.getInfo()->extraInfo->constCast<BoundExtraAddPropertyInfo>();
        defaultValueEvaluator = exprMapper.getEvaluator(addPropInfo.boundDefault);
    }
    auto printInfo = std::make_unique<LogicalAlterPrintInfo>(alter.getInfo()->copy());
    auto messageTable = FactorizedTableUtils::getSingleStringColumnFTable(
        storage::MemoryManager::Get(*clientContext));
    return std::make_unique<Alter>(alter.getInfo()->copy(), std::move(defaultValueEvaluator),
        std::move(messageTable), getOperatorID(), std::move(printInfo));
}

} // namespace processor
} // namespace lbug
