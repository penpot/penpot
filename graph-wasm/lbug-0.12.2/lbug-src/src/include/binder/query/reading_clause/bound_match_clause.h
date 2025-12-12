#pragma once

#include "binder/query/query_graph.h"
#include "bound_join_hint.h"
#include "bound_reading_clause.h"

namespace lbug {
namespace binder {

class LBUG_API BoundMatchClause final : public BoundReadingClause {
    static constexpr common::ClauseType clauseType_ = common::ClauseType::MATCH;

public:
    BoundMatchClause(QueryGraphCollection collection, common::MatchClauseType matchClauseType)
        : BoundReadingClause{clauseType_}, collection{std::move(collection)},
          matchClauseType{matchClauseType} {}

    QueryGraphCollection* getQueryGraphCollectionUnsafe() { return &collection; }
    const QueryGraphCollection* getQueryGraphCollection() const { return &collection; }

    common::MatchClauseType getMatchClauseType() const { return matchClauseType; }

    void setHint(std::shared_ptr<BoundJoinHintNode> root) { hintRoot = std::move(root); }
    bool hasHint() const { return hintRoot != nullptr; }
    std::shared_ptr<BoundJoinHintNode> getHint() const { return hintRoot; }

private:
    QueryGraphCollection collection;
    common::MatchClauseType matchClauseType;
    std::shared_ptr<BoundJoinHintNode> hintRoot;
};

} // namespace binder
} // namespace lbug
