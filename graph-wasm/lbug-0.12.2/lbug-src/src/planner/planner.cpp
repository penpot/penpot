#include "planner/planner.h"

#include "main/client_context.h"
#include "main/database.h"

using namespace lbug::binder;
using namespace lbug::catalog;
using namespace lbug::common;
using namespace lbug::storage;

namespace lbug {
namespace planner {

bool QueryGraphPlanningInfo::containsCorrExpr(const Expression& expr) const {
    for (auto& corrExpr : corrExprs) {
        if (*corrExpr == expr) {
            return true;
        }
    }
    return false;
}

expression_vector PropertyExprCollection::getProperties(const Expression& pattern) const {
    if (!patternNameToProperties.contains(pattern.getUniqueName())) {
        return binder::expression_vector{};
    }
    return patternNameToProperties.at(pattern.getUniqueName());
}

expression_vector PropertyExprCollection::getProperties() const {
    expression_vector result;
    for (auto& [_, exprs] : patternNameToProperties) {
        for (auto& expr : exprs) {
            result.push_back(expr);
        }
    }
    return result;
}

void PropertyExprCollection::addProperties(const std::string& patternName,
    std::shared_ptr<Expression> property) {
    if (!patternNameToProperties.contains(patternName)) {
        patternNameToProperties.insert({patternName, expression_vector{}});
    }
    for (auto& p : patternNameToProperties.at(patternName)) {
        if (*p == *property) {
            return;
        }
    }
    patternNameToProperties.at(patternName).push_back(property);
}

void PropertyExprCollection::clear() {
    patternNameToProperties.clear();
}

Planner::Planner(main::ClientContext* clientContext)
    : clientContext{clientContext}, cardinalityEstimator{clientContext}, context{},
      plannerExtensions{clientContext->getDatabase()->getPlannerExtensions()} {}

LogicalPlan Planner::planStatement(const BoundStatement& statement) {
    switch (statement.getStatementType()) {
    case StatementType::QUERY: {
        return planQuery(statement);
    }
    case StatementType::CREATE_TABLE: {
        return planCreateTable(statement);
    }
    case StatementType::CREATE_SEQUENCE: {
        return planCreateSequence(statement);
    }
    case StatementType::CREATE_TYPE: {
        return planCreateType(statement);
    }
    case StatementType::COPY_FROM: {
        return planCopyFrom(statement);
    }
    case StatementType::COPY_TO: {
        return planCopyTo(statement);
    }
    case StatementType::DROP: {
        return planDrop(statement);
    }
    case StatementType::ALTER: {
        return planAlter(statement);
    }
    case StatementType::STANDALONE_CALL: {
        return planStandaloneCall(statement);
    }
    case StatementType::STANDALONE_CALL_FUNCTION: {
        return planStandaloneCallFunction(statement);
    }
    case StatementType::EXPLAIN: {
        return planExplain(statement);
    }
    case StatementType::CREATE_MACRO: {
        return planCreateMacro(statement);
    }
    case StatementType::TRANSACTION: {
        return planTransaction(statement);
    }
    case StatementType::EXTENSION: {
        return planExtension(statement);
    }
    case StatementType::EXPORT_DATABASE: {
        return planExportDatabase(statement);
    }
    case StatementType::IMPORT_DATABASE: {
        return planImportDatabase(statement);
    }
    case StatementType::ATTACH_DATABASE: {
        return planAttachDatabase(statement);
    }
    case StatementType::DETACH_DATABASE: {
        return planDetachDatabase(statement);
    }
    case StatementType::USE_DATABASE: {
        return planUseDatabase(statement);
    }
    case StatementType::EXTENSION_CLAUSE: {
        return planExtensionClause(statement);
    }
    default:
        KU_UNREACHABLE;
    }
}

} // namespace planner
} // namespace lbug
