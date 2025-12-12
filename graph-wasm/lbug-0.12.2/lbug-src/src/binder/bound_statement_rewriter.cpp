#include "binder/bound_statement_rewriter.h"

#include "binder/rewriter/match_clause_pattern_label_rewriter.h"
#include "binder/rewriter/normalized_query_part_match_rewriter.h"
#include "binder/rewriter/with_clause_projection_rewriter.h"
#include "binder/visitor/default_type_solver.h"

namespace lbug {
namespace binder {

void BoundStatementRewriter::rewrite(BoundStatement& boundStatement,
    main::ClientContext& clientContext) {
    auto withClauseProjectionRewriter = WithClauseProjectionRewriter();
    withClauseProjectionRewriter.visitUnsafe(boundStatement);

    auto normalizedQueryPartMatchRewriter = NormalizedQueryPartMatchRewriter(&clientContext);
    normalizedQueryPartMatchRewriter.visitUnsafe(boundStatement);

    auto matchClausePatternLabelRewriter = MatchClausePatternLabelRewriter(clientContext);
    matchClausePatternLabelRewriter.visitUnsafe(boundStatement);

    auto defaultTypeSolver = DefaultTypeSolver();
    defaultTypeSolver.visit(boundStatement);
}

} // namespace binder
} // namespace lbug
