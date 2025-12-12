#pragma once

#include "binder/query/normalized_single_query.h"
#include "bound_statement.h"

namespace lbug {
namespace binder {

class LBUG_API BoundStatementVisitor {
public:
    BoundStatementVisitor() = default;
    virtual ~BoundStatementVisitor() = default;

    void visit(const BoundStatement& statement);
    // Unsafe visitors are implemented on-demand. We may reuse safe visitor inside unsafe visitor
    // if no other class need to overwrite an unsafe visitor.
    void visitUnsafe(BoundStatement& statement);

    virtual void visitSingleQuery(const NormalizedSingleQuery& singleQuery);

protected:
    virtual void visitCreateSequence(const BoundStatement&) {}
    virtual void visitCreateTable(const BoundStatement&) {}
    virtual void visitDrop(const BoundStatement&) {}
    virtual void visitCreateType(const BoundStatement&) {}
    virtual void visitAlter(const BoundStatement&) {}
    virtual void visitCopyFrom(const BoundStatement&);
    virtual void visitCopyTo(const BoundStatement&);
    virtual void visitExportDatabase(const BoundStatement&) {}
    virtual void visitImportDatabase(const BoundStatement&) {}
    virtual void visitStandaloneCall(const BoundStatement&) {}
    virtual void visitExplain(const BoundStatement&);
    virtual void visitCreateMacro(const BoundStatement&) {}
    virtual void visitTransaction(const BoundStatement&) {}
    virtual void visitExtension(const BoundStatement&) {}

    virtual void visitRegularQuery(const BoundStatement& statement);
    virtual void visitRegularQueryUnsafe(BoundStatement& statement);
    virtual void visitSingleQueryUnsafe(NormalizedSingleQuery& singleQuery);
    virtual void visitQueryPart(const NormalizedQueryPart& queryPart);
    virtual void visitQueryPartUnsafe(NormalizedQueryPart& queryPart);
    void visitReadingClause(const BoundReadingClause& readingClause);
    void visitReadingClauseUnsafe(BoundReadingClause& readingClause);
    virtual void visitMatch(const BoundReadingClause&) {}
    virtual void visitMatchUnsafe(BoundReadingClause&) {}
    virtual void visitUnwind(const BoundReadingClause& /*readingClause*/) {}
    virtual void visitTableFunctionCall(const BoundReadingClause&) {}
    virtual void visitLoadFrom(const BoundReadingClause& /*statement*/) {}
    void visitUpdatingClause(const BoundUpdatingClause& updatingClause);
    virtual void visitSet(const BoundUpdatingClause& /*updatingClause*/) {}
    virtual void visitDelete(const BoundUpdatingClause& /* updatingClause*/) {}
    virtual void visitInsert(const BoundUpdatingClause& /* updatingClause*/) {}
    virtual void visitMerge(const BoundUpdatingClause& /* updatingClause*/) {}

    virtual void visitProjectionBody(const BoundProjectionBody& /* projectionBody*/) {}
    virtual void visitProjectionBodyPredicate(const std::shared_ptr<Expression>& /* predicate*/) {}
    virtual void visitAttachDatabase(const BoundStatement&) {}
    virtual void visitDetachDatabase(const BoundStatement&) {}
    virtual void visitUseDatabase(const BoundStatement&) {}
    virtual void visitStandaloneCallFunction(const BoundStatement&) {}
    virtual void visitExtensionClause(const BoundStatement&) {}
};

} // namespace binder
} // namespace lbug
