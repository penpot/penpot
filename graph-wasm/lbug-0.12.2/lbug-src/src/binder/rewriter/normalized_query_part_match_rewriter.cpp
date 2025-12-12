#include "binder/rewriter/normalized_query_part_match_rewriter.h"

#include "binder/binder.h"
#include "binder/query/reading_clause/bound_match_clause.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

static bool canRewrite(const BoundMatchClause& matchClause) {
    return !matchClause.hasHint() &&
           matchClause.getMatchClauseType() != MatchClauseType::OPTIONAL_MATCH;
}

void NormalizedQueryPartMatchRewriter::visitQueryPartUnsafe(NormalizedQueryPart& queryPart) {
    if (queryPart.getNumReadingClause() == 0) {
        return;
    }
    for (auto i = 0u; i < queryPart.getNumReadingClause(); i++) {
        if (queryPart.getReadingClause(i)->getClauseType() != ClauseType::MATCH) {
            return;
        }
        auto& match = queryPart.getReadingClause(i)->constCast<BoundMatchClause>();
        if (!canRewrite(match)) {
            return;
        }
    }
    // Merge consecutive match clauses
    std::vector<std::unique_ptr<BoundReadingClause>> newReadingClauses;
    newReadingClauses.push_back(std::move(queryPart.readingClauses[0]));
    auto& leadingMatchClause = newReadingClauses[0]->cast<BoundMatchClause>();
    auto binder = Binder(clientContext);
    auto expressionBinder = binder.getExpressionBinder();
    for (auto idx = 1u; idx < queryPart.getNumReadingClause(); idx++) {
        auto& otherMatchClause = queryPart.readingClauses[idx]->constCast<BoundMatchClause>();
        leadingMatchClause.getQueryGraphCollectionUnsafe()->merge(
            *otherMatchClause.getQueryGraphCollection());
        auto predicate = expressionBinder->combineBooleanExpressions(ExpressionType::AND,
            leadingMatchClause.getPredicate(), otherMatchClause.getPredicate());
        leadingMatchClause.setPredicate(std::move(predicate));
    }
    // Move remaining reading clause
    queryPart.readingClauses = std::move(newReadingClauses);
}

} // namespace binder
} // namespace lbug
