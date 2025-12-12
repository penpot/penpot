#include "binder/bound_statement_visitor.h"

#include "binder/bound_explain.h"
#include "binder/copy/bound_copy_from.h"
#include "binder/copy/bound_copy_to.h"
#include "binder/query/bound_regular_query.h"
#include "common/cast.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

void BoundStatementVisitor::visit(const BoundStatement& statement) {
    switch (statement.getStatementType()) {
    case StatementType::QUERY: {
        visitRegularQuery(statement);
    } break;
    case StatementType::CREATE_SEQUENCE: {
        visitCreateSequence(statement);
    } break;
    case StatementType::DROP: {
        visitDrop(statement);
    } break;
    case StatementType::CREATE_TABLE: {
        visitCreateTable(statement);
    } break;
    case StatementType::CREATE_TYPE: {
        visitCreateType(statement);
    } break;
    case StatementType::ALTER: {
        visitAlter(statement);
    } break;
    case StatementType::COPY_FROM: {
        visitCopyFrom(statement);
    } break;
    case StatementType::COPY_TO: {
        visitCopyTo(statement);
    } break;
    case StatementType::STANDALONE_CALL: {
        visitStandaloneCall(statement);
    } break;
    case StatementType::EXPLAIN: {
        visitExplain(statement);
    } break;
    case StatementType::CREATE_MACRO: {
        visitCreateMacro(statement);
    } break;
    case StatementType::TRANSACTION: {
        visitTransaction(statement);
    } break;
    case StatementType::EXTENSION: {
        visitExtension(statement);
    } break;
    case StatementType::EXPORT_DATABASE: {
        visitExportDatabase(statement);
    } break;
    case StatementType::IMPORT_DATABASE: {
        visitImportDatabase(statement);
    } break;
    case StatementType::ATTACH_DATABASE: {
        visitAttachDatabase(statement);
    } break;
    case StatementType::DETACH_DATABASE: {
        visitDetachDatabase(statement);
    } break;
    case StatementType::USE_DATABASE: {
        visitUseDatabase(statement);
    } break;
    case StatementType::STANDALONE_CALL_FUNCTION: {
        visitStandaloneCallFunction(statement);
    } break;
    case StatementType::EXTENSION_CLAUSE: {
        visitExtensionClause(statement);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void BoundStatementVisitor::visitUnsafe(BoundStatement& statement) {
    switch (statement.getStatementType()) {
    case StatementType::QUERY: {
        visitRegularQueryUnsafe(statement);
    } break;
    default:
        break;
    }
}

void BoundStatementVisitor::visitCopyFrom(const BoundStatement& statement) {
    auto& copyFrom = ku_dynamic_cast<const BoundCopyFrom&>(statement);
    if (copyFrom.getInfo()->source->type == ScanSourceType::QUERY) {
        auto querySource = ku_dynamic_cast<BoundQueryScanSource*>(copyFrom.getInfo()->source.get());
        visit(*querySource->statement);
    }
}

void BoundStatementVisitor::visitCopyTo(const BoundStatement& statement) {
    auto& copyTo = ku_dynamic_cast<const BoundCopyTo&>(statement);
    visitRegularQuery(*copyTo.getRegularQuery());
}

void BoundStatementVisitor::visitRegularQuery(const BoundStatement& statement) {
    auto& regularQuery = ku_dynamic_cast<const BoundRegularQuery&>(statement);
    for (auto i = 0u; i < regularQuery.getNumSingleQueries(); ++i) {
        visitSingleQuery(*regularQuery.getSingleQuery(i));
    }
}

void BoundStatementVisitor::visitRegularQueryUnsafe(BoundStatement& statement) {
    auto& regularQuery = statement.cast<BoundRegularQuery>();
    for (auto i = 0u; i < regularQuery.getNumSingleQueries(); ++i) {
        visitSingleQueryUnsafe(*regularQuery.getSingleQueryUnsafe(i));
    }
}

void BoundStatementVisitor::visitSingleQuery(const NormalizedSingleQuery& singleQuery) {
    for (auto i = 0u; i < singleQuery.getNumQueryParts(); ++i) {
        visitQueryPart(*singleQuery.getQueryPart(i));
    }
}

void BoundStatementVisitor::visitSingleQueryUnsafe(NormalizedSingleQuery& singleQuery) {
    for (auto i = 0u; i < singleQuery.getNumQueryParts(); ++i) {
        visitQueryPartUnsafe(*singleQuery.getQueryPartUnsafe(i));
    }
}

void BoundStatementVisitor::visitQueryPart(const NormalizedQueryPart& queryPart) {
    for (auto i = 0u; i < queryPart.getNumReadingClause(); ++i) {
        visitReadingClause(*queryPart.getReadingClause(i));
    }
    for (auto i = 0u; i < queryPart.getNumUpdatingClause(); ++i) {
        visitUpdatingClause(*queryPart.getUpdatingClause(i));
    }
    if (queryPart.hasProjectionBody()) {
        visitProjectionBody(*queryPart.getProjectionBody());
        if (queryPart.hasProjectionBodyPredicate()) {
            visitProjectionBodyPredicate(queryPart.getProjectionBodyPredicate());
        }
    }
}

void BoundStatementVisitor::visitQueryPartUnsafe(NormalizedQueryPart& queryPart) {
    for (auto i = 0u; i < queryPart.getNumReadingClause(); ++i) {
        visitReadingClauseUnsafe(*queryPart.getReadingClause(i));
    }
    for (auto i = 0u; i < queryPart.getNumUpdatingClause(); ++i) {
        visitUpdatingClause(*queryPart.getUpdatingClause(i));
    }
    if (queryPart.hasProjectionBody()) {
        visitProjectionBody(*queryPart.getProjectionBody());
        if (queryPart.hasProjectionBodyPredicate()) {
            visitProjectionBodyPredicate(queryPart.getProjectionBodyPredicate());
        }
    }
}

void BoundStatementVisitor::visitExplain(const BoundStatement& statement) {
    visit(*(statement.constCast<BoundExplain>()).getStatementToExplain());
}

void BoundStatementVisitor::visitReadingClause(const BoundReadingClause& readingClause) {
    switch (readingClause.getClauseType()) {
    case ClauseType::MATCH: {
        visitMatch(readingClause);
    } break;
    case ClauseType::UNWIND: {
        visitUnwind(readingClause);
    } break;
    case ClauseType::TABLE_FUNCTION_CALL: {
        visitTableFunctionCall(readingClause);
    } break;
    case ClauseType::LOAD_FROM: {
        visitLoadFrom(readingClause);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void BoundStatementVisitor::visitReadingClauseUnsafe(BoundReadingClause& readingClause) {
    switch (readingClause.getClauseType()) {
    case ClauseType::MATCH: {
        visitMatchUnsafe(readingClause);
    } break;
    case ClauseType::UNWIND: {
        visitUnwind(readingClause);
    } break;
    case ClauseType::TABLE_FUNCTION_CALL: {
        visitTableFunctionCall(readingClause);
    } break;
    case ClauseType::LOAD_FROM: {
        visitLoadFrom(readingClause);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

void BoundStatementVisitor::visitUpdatingClause(const BoundUpdatingClause& updatingClause) {
    switch (updatingClause.getClauseType()) {
    case ClauseType::SET: {
        visitSet(updatingClause);
    } break;
    case ClauseType::DELETE_: {
        visitDelete(updatingClause);
    } break;
    case ClauseType::INSERT: {
        visitInsert(updatingClause);
    } break;
    case ClauseType::MERGE: {
        visitMerge(updatingClause);
    } break;
    default:
        KU_UNREACHABLE;
    }
}

} // namespace binder
} // namespace lbug
