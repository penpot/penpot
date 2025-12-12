#pragma once

#include "binder/bound_statement_visitor.h"

namespace lbug {
namespace binder {

// Collect all property expressions for a given statement.
class LBUG_API PropertyCollector final : public BoundStatementVisitor {
public:
    expression_vector getProperties() const;

    // Skip collecting node/rel properties if they are in WITH projection list.
    // See with_clause_projection_rewriter for more details.
    void visitSingleQuerySkipNodeRel(const NormalizedSingleQuery& singleQuery);

private:
    void visitQueryPartSkipNodeRel(const NormalizedQueryPart& queryPart);

    void visitMatch(const BoundReadingClause& readingClause) override;
    void visitUnwind(const BoundReadingClause& readingClause) override;
    void visitLoadFrom(const BoundReadingClause& readingClause) override;
    void visitTableFunctionCall(const BoundReadingClause&) override;

    void visitSet(const BoundUpdatingClause& updatingClause) override;
    void visitDelete(const BoundUpdatingClause& updatingClause) override;
    void visitInsert(const BoundUpdatingClause& updatingClause) override;
    void visitMerge(const BoundUpdatingClause& updatingClause) override;

    void visitProjectionBodySkipNodeRel(const BoundProjectionBody& projectionBody);
    void visitProjectionBody(const BoundProjectionBody& projectionBody) override;
    void visitProjectionBodyPredicate(const std::shared_ptr<Expression>& predicate) override;

    void collectProperties(const std::shared_ptr<Expression>& expression);
    void collectPropertiesSkipNodeRel(const std::shared_ptr<Expression>& expression);

private:
    expression_set properties;
};

} // namespace binder
} // namespace lbug
