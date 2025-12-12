#pragma once

#include "parser/expression/parsed_expression.h"
#include "updating_clause.h"

namespace lbug {
namespace parser {

class SetClause final : public UpdatingClause {
public:
    SetClause() : UpdatingClause{common::ClauseType::SET} {};

    inline void addSetItem(parsed_expr_pair setItem) { setItems.push_back(std::move(setItem)); }
    inline const std::vector<parsed_expr_pair>& getSetItemsRef() const { return setItems; }

private:
    std::vector<parsed_expr_pair> setItems;
};

} // namespace parser
} // namespace lbug
