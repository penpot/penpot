#include "binder/bound_attach_database.h"
#include "binder/bound_create_macro.h"
#include "binder/bound_detach_database.h"
#include "binder/bound_explain.h"
#include "binder/bound_extension_statement.h"
#include "binder/bound_standalone_call.h"
#include "binder/bound_standalone_call_function.h"
#include "binder/bound_transaction_statement.h"
#include "binder/bound_use_database.h"
#include "binder/ddl/bound_alter.h"
#include "binder/ddl/bound_create_sequence.h"
#include "binder/ddl/bound_create_table.h"
#include "binder/ddl/bound_create_type.h"
#include "binder/ddl/bound_drop.h"
#include "extension/planner_extension.h"
#include "planner/operator/ddl/logical_alter.h"
#include "planner/operator/ddl/logical_create_sequence.h"
#include "planner/operator/ddl/logical_create_table.h"
#include "planner/operator/ddl/logical_create_type.h"
#include "planner/operator/ddl/logical_drop.h"
#include "planner/operator/logical_create_macro.h"
#include "planner/operator/logical_explain.h"
#include "planner/operator/logical_noop.h"
#include "planner/operator/logical_standalone_call.h"
#include "planner/operator/logical_table_function_call.h"
#include "planner/operator/logical_transaction.h"
#include "planner/operator/simple/logical_attach_database.h"
#include "planner/operator/simple/logical_detach_database.h"
#include "planner/operator/simple/logical_extension.h"
#include "planner/operator/simple/logical_use_database.h"
#include "planner/planner.h"

using namespace lbug::binder;
using namespace lbug::common;

namespace lbug {
namespace planner {

static LogicalPlan getSimplePlan(std::shared_ptr<LogicalOperator> op) {
    LogicalPlan plan;
    op->computeFactorizedSchema();
    plan.setLastOperator(std::move(op));
    return plan;
}

LogicalPlan Planner::planCreateTable(const BoundStatement& statement) {
    auto& createTable = statement.constCast<BoundCreateTable>();
    auto& info = createTable.getInfo();
    // If it is a CREATE NODE TABLE AS, then copy as well
    if (createTable.hasCopyInfo()) {
        std::vector<std::shared_ptr<LogicalOperator>> children;
        switch (info.type) {
        case catalog::CatalogEntryType::NODE_TABLE_ENTRY: {
            children.push_back(planCopyNodeFrom(&createTable.getCopyInfo()).getLastOperator());
        } break;
        case catalog::CatalogEntryType::REL_GROUP_ENTRY: {
            children.push_back(planCopyRelFrom(&createTable.getCopyInfo()).getLastOperator());
        } break;
        default: {
            KU_UNREACHABLE;
        }
        }
        auto create = std::make_shared<LogicalCreateTable>(info.copy());
        children.push_back(std::move(create));
        auto noop = std::make_shared<LogicalNoop>(children.size() - 1, children);
        return getSimplePlan(std::move(noop));
    }
    auto op = std::make_shared<LogicalCreateTable>(info.copy());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planCreateType(const BoundStatement& statement) {
    auto& createType = statement.constCast<BoundCreateType>();
    auto op =
        std::make_shared<LogicalCreateType>(createType.getName(), createType.getType().copy());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planCreateSequence(const BoundStatement& statement) {
    auto& createSequence = statement.constCast<BoundCreateSequence>();
    auto& info = createSequence.getInfo();
    auto op = std::make_shared<LogicalCreateSequence>(info.copy());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planDrop(const BoundStatement& statement) {
    auto& dropTable = statement.constCast<BoundDrop>();
    auto op = std::make_shared<LogicalDrop>(dropTable.getDropInfo());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planAlter(const BoundStatement& statement) {
    auto& alter = statement.constCast<BoundAlter>();
    auto op = std::make_shared<LogicalAlter>(alter.getInfo().copy());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planStandaloneCall(const BoundStatement& statement) {
    auto& standaloneCallClause = statement.constCast<BoundStandaloneCall>();
    auto op = std::make_shared<LogicalStandaloneCall>(standaloneCallClause.getOption(),
        standaloneCallClause.getOptionValue());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planStandaloneCallFunction(const BoundStatement& statement) {
    auto& standaloneCallFunctionClause = statement.constCast<BoundStandaloneCallFunction>();
    auto op =
        std::make_shared<LogicalTableFunctionCall>(standaloneCallFunctionClause.getTableFunction(),
            standaloneCallFunctionClause.getBindData()->copy());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planExplain(const BoundStatement& statement) {
    auto& explain = statement.constCast<BoundExplain>();
    auto statementToExplain = explain.getStatementToExplain();
    auto planToExplain = planStatement(*statementToExplain);
    auto op = std::make_shared<LogicalExplain>(planToExplain.getLastOperator(),
        explain.getExplainType(), statementToExplain->getStatementResult()->getColumns());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planCreateMacro(const BoundStatement& statement) {
    auto& createMacro = statement.constCast<BoundCreateMacro>();
    auto op =
        std::make_shared<LogicalCreateMacro>(createMacro.getMacroName(), createMacro.getMacro());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planTransaction(const BoundStatement& statement) {
    auto& transactionStatement = statement.constCast<BoundTransactionStatement>();
    auto op = std::make_shared<LogicalTransaction>(transactionStatement.getTransactionAction());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planExtension(const BoundStatement& statement) {
    auto& extensionStatement = statement.constCast<BoundExtensionStatement>();
    auto op = std::make_shared<LogicalExtension>(extensionStatement.getAuxInfo());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planAttachDatabase(const BoundStatement& statement) {
    auto& boundAttachDatabase = statement.constCast<BoundAttachDatabase>();
    auto op = std::make_shared<LogicalAttachDatabase>(boundAttachDatabase.getAttachInfo());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planDetachDatabase(const BoundStatement& statement) {
    auto& boundDetachDatabase = statement.constCast<BoundDetachDatabase>();
    auto op = std::make_shared<LogicalDetachDatabase>(boundDetachDatabase.getDBName());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planUseDatabase(const BoundStatement& statement) {
    auto& boundUseDatabase = statement.constCast<BoundUseDatabase>();
    auto op = std::make_shared<LogicalUseDatabase>(boundUseDatabase.getDBName());
    return getSimplePlan(std::move(op));
}

LogicalPlan Planner::planExtensionClause(const BoundStatement& statement) {
    for (auto& plannerExtension : plannerExtensions) {
        auto op = plannerExtension->plan(statement);
        if (op) {
            return getSimplePlan(op);
        }
    }
    KU_UNREACHABLE;
}

} // namespace planner
} // namespace lbug
