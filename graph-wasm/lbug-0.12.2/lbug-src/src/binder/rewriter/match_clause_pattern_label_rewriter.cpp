#include "binder/rewriter/match_clause_pattern_label_rewriter.h"

#include "binder/query/reading_clause/bound_match_clause.h"

using namespace lbug::common;

namespace lbug {
namespace binder {

void MatchClausePatternLabelRewriter::visitMatchUnsafe(BoundReadingClause& readingClause) {
    auto& matchClause = readingClause.cast<BoundMatchClause>();
    if (matchClause.getMatchClauseType() == MatchClauseType::OPTIONAL_MATCH) {
        return;
    }
    auto collection = matchClause.getQueryGraphCollectionUnsafe();
    for (auto i = 0u; i < collection->getNumQueryGraphs(); ++i) {
        analyzer.pruneLabel(*collection->getQueryGraphUnsafe(i));
    }
}

} // namespace binder
} // namespace lbug
