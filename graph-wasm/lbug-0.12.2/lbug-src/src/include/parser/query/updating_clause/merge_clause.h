#pragma once

#include "parser/query/graph_pattern/pattern_element.h"
#include "updating_clause.h"

namespace lbug {
namespace parser {

class MergeClause final : public UpdatingClause {
public:
    explicit MergeClause(std::vector<PatternElement> patternElements)
        : UpdatingClause{common::ClauseType::MERGE}, patternElements{std::move(patternElements)} {}

    inline const std::vector<PatternElement>& getPatternElementsRef() const {
        return patternElements;
    }
    inline void addOnMatchSetItems(parsed_expr_pair setItem) {
        onMatchSetItems.push_back(std::move(setItem));
    }
    inline bool hasOnMatchSetItems() const { return !onMatchSetItems.empty(); }
    inline const std::vector<parsed_expr_pair>& getOnMatchSetItemsRef() const {
        return onMatchSetItems;
    }

    inline void addOnCreateSetItems(parsed_expr_pair setItem) {
        onCreateSetItems.push_back(std::move(setItem));
    }
    inline bool hasOnCreateSetItems() const { return !onCreateSetItems.empty(); }
    inline const std::vector<parsed_expr_pair>& getOnCreateSetItemsRef() const {
        return onCreateSetItems;
    }

private:
    std::vector<PatternElement> patternElements;
    std::vector<parsed_expr_pair> onMatchSetItems;
    std::vector<parsed_expr_pair> onCreateSetItems;
};

} // namespace parser
} // namespace lbug
