#pragma once

#include "parser/query/graph_pattern/pattern_element.h"
#include "updating_clause.h"

namespace lbug {
namespace parser {

class InsertClause final : public UpdatingClause {
public:
    explicit InsertClause(std::vector<PatternElement> patternElements)
        : UpdatingClause{common::ClauseType::INSERT},
          patternElements{std::move(patternElements)} {};

    inline const std::vector<PatternElement>& getPatternElementsRef() const {
        return patternElements;
    }

private:
    std::vector<PatternElement> patternElements;
};

} // namespace parser
} // namespace lbug
