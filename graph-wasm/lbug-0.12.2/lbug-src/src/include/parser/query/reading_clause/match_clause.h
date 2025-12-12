#pragma once

#include "join_hint.h"
#include "parser/query/graph_pattern/pattern_element.h"
#include "reading_clause.h"

namespace lbug {
namespace parser {

class MatchClause : public ReadingClause {
    static constexpr common::ClauseType clauseType_ = common::ClauseType::MATCH;

public:
    MatchClause(std::vector<PatternElement> patternElements,
        common::MatchClauseType matchClauseType)
        : ReadingClause{clauseType_}, patternElements{std::move(patternElements)},
          matchClauseType{matchClauseType} {}

    const std::vector<PatternElement>& getPatternElementsRef() const { return patternElements; }

    common::MatchClauseType getMatchClauseType() const { return matchClauseType; }

    void setHint(std::shared_ptr<JoinHintNode> root) { hintRoot = std::move(root); }
    bool hasHint() const { return hintRoot != nullptr; }
    std::shared_ptr<JoinHintNode> getHint() const { return hintRoot; }

private:
    std::vector<PatternElement> patternElements;
    common::MatchClauseType matchClauseType;
    std::shared_ptr<JoinHintNode> hintRoot = nullptr;
};

} // namespace parser
} // namespace lbug
